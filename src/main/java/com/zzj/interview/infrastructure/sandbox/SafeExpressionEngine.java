package com.zzj.interview.infrastructure.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 安全代码沙箱（受限表达式求值引擎）
 *
 * 目标：让AI可以通过Function Calling执行"计算类"代码（算术、比较、内置数学函数），
 * 同时把执行严格限制在一个安全边界内。
 *
 * 安全边界（纵深防御）：
 * 1. 语法白名单：仅支持数字/变量/算术(+ - * / %)/比较/括号/内置函数，
 *    没有赋值、循环、函数定义、字符串拼接、任何IO/反射/类加载能力——
 *    因为它是一个在固定文法上的解释器，而非通用语言运行时。
 * 2. 源码长度上限：{@link #MAX_SOURCE_LEN}
 * 3. 步数预算：解析+求值的节点数超过 {@link #MAX_STEPS} 立即中止，杜绝深层嵌套/栈溢出攻击。
 * 4. 硬超时：在守护线程上执行，超过 {@link #TIMEOUT_MS} 强制取消。
 * 5. 变量隔离：变量值只读、且强制转成数值，无法注入代码。
 *
 * 说明：这是一个"演示级"的受限沙箱，足以安全承载客服场景的计算诉求
 * （如退款金额、天数差、单位换算）。若要执行通用Python，应改用进程级隔离
 * （gVisor/Firecracker/独立容器 + seccomp + 资源限额），不在本Demo范围内。
 */
public final class SafeExpressionEngine {

    private static final Logger log = LoggerFactory.getLogger(SafeExpressionEngine.class);

    private static final int MAX_SOURCE_LEN = 500;
    private static final int MAX_STEPS = 20_000;
    private static final long TIMEOUT_MS = 1_000L;

    /** 共享守护线程池：用于对求值施加硬超时 */
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "code-sandbox-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    /** 内置函数白名单：名称 -> 元数（-1 表示变参，至少2个） */
    private static final Map<String, Integer> FUNCTIONS = new HashMap<>();
    static {
        FUNCTIONS.put("abs", 1);
        FUNCTIONS.put("sqrt", 1);
        FUNCTIONS.put("floor", 1);
        FUNCTIONS.put("ceil", 1);
        FUNCTIONS.put("round", 1);
        FUNCTIONS.put("log", 1);
        FUNCTIONS.put("ln", 1);
        FUNCTIONS.put("log10", 1);
        FUNCTIONS.put("sin", 1);
        FUNCTIONS.put("cos", 1);
        FUNCTIONS.put("tan", 1);
        FUNCTIONS.put("exp", 1);
        FUNCTIONS.put("pow", 2);
        FUNCTIONS.put("min", -1);
        FUNCTIONS.put("max", -1);
    }

    /**
     * 求值结果
     *
     * @param success   是否成功
     * @param value     成功时的结果（Double 或 Boolean），已格式化为字符串呈现给AI
     * @param error     失败原因
     * @param elapsedMs 执行耗时
     */
    public record EvalResult(boolean success, String value, String error, long elapsedMs) {
        public static EvalResult ok(String value, long elapsedMs) {
            return new EvalResult(true, value, null, elapsedMs);
        }
        public static EvalResult fail(String error, long elapsedMs) {
            return new EvalResult(false, null, error, elapsedMs);
        }
    }

    /**
     * 在沙箱中求值一个表达式
     *
     * @param expression 受限表达式，如 "(299 * 0.8) + 12" 或 "max(a, b) - min(a, b)"
     * @param variables  只读变量表（值会被强制转为数值）
     */
    public EvalResult evaluate(String expression, Map<String, Object> variables) {
        long start = System.currentTimeMillis();
        if (expression == null || expression.isBlank()) {
            return EvalResult.fail("表达式为空", elapsed(start));
        }
        if (expression.length() > MAX_SOURCE_LEN) {
            return EvalResult.fail("表达式超过最大长度 " + MAX_SOURCE_LEN, elapsed(start));
        }

        // 变量快照 + 数值化（隔离外部对象，杜绝注入）
        final Map<String, Double> vars = new HashMap<>();
        if (variables != null) {
            for (Map.Entry<String, Object> e : variables.entrySet()) {
                Double d = toNumber(e.getValue());
                if (d == null) {
                    return EvalResult.fail("变量 " + e.getKey() + " 不是数值，沙箱只支持数值变量", elapsed(start));
                }
                vars.put(e.getKey(), d);
            }
        }

        Future<Object> future = EXECUTOR.submit((Callable<Object>) () -> {
            AtomicInteger steps = new AtomicInteger(0);
            List<Token> tokens = tokenize(expression);
            Parser parser = new Parser(tokens, vars, steps);
            Object result = parser.parseAndEvaluate();
            return result;
        });

        try {
            Object raw = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return EvalResult.ok(format(raw), elapsed(start));
        } catch (TimeoutException te) {
            future.cancel(true);
            log.warn("沙箱执行超时(>{}ms)，已强制中止: {}", TIMEOUT_MS, expression);
            return EvalResult.fail("执行超时（超过 " + TIMEOUT_MS + "ms），已中止", elapsed(start));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            return EvalResult.fail(msg, elapsed(start));
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private static Double toNumber(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        if (v instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        return null;
    }

    private static String format(Object raw) {
        if (raw instanceof Boolean b) {
            return b.toString();
        }
        if (raw instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                return d.toString();
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d.doubleValue());
            }
            return String.valueOf(d);
        }
        return String.valueOf(raw);
    }

    // ==================== 词法分析 ====================

    private enum TokenType { NUMBER, IDENT, OP, LPAREN, RPAREN, COMMA, EOF }

    private record Token(TokenType type, String text, double number) {}

    private static List<Token> tokenize(String src) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                int j = i;
                while (j < n && (Character.isDigit(src.charAt(j)) || src.charAt(j) == '.')) {
                    j++;
                }
                String num = src.substring(i, j);
                try {
                    tokens.add(new Token(TokenType.NUMBER, num, Double.parseDouble(num)));
                } catch (NumberFormatException e) {
                    throw new SandboxException("非法数字: " + num);
                }
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_')) {
                    j++;
                }
                tokens.add(new Token(TokenType.IDENT, src.substring(i, j), 0));
                i = j;
                continue;
            }
            // 双字符运算符
            if (i + 1 < n) {
                String two = src.substring(i, i + 2);
                if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")) {
                    tokens.add(new Token(TokenType.OP, two, 0));
                    i += 2;
                    continue;
                }
            }
            if (c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '<' || c == '>') {
                tokens.add(new Token(TokenType.OP, String.valueOf(c), 0));
                i++;
                continue;
            }
            if (c == '(') { tokens.add(new Token(TokenType.LPAREN, "(", 0)); i++; continue; }
            if (c == ')') { tokens.add(new Token(TokenType.RPAREN, ")", 0)); i++; continue; }
            if (c == ',') { tokens.add(new Token(TokenType.COMMA, ",", 0)); i++; continue; }
            throw new SandboxException("不支持的字符: '" + c + "'（沙箱仅允许数字/变量/算术与比较运算符/括号/逗号）");
        }
        tokens.add(new Token(TokenType.EOF, "", 0));
        return tokens;
    }

    // ==================== 语法分析 + 求值（Pratt/递归下降） ====================

    private static final class Parser {
        private final List<Token> tokens;
        private final Map<String, Double> vars;
        private final AtomicInteger steps;
        private int pos;

        Parser(List<Token> tokens, Map<String, Double> vars, AtomicInteger steps) {
            this.tokens = tokens;
            this.vars = vars;
            this.steps = steps;
        }

        private void tick() {
            if (steps.incrementAndGet() > MAX_STEPS) {
                throw new SandboxException("计算步数超过预算 " + MAX_STEPS + "，已中止");
            }
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            return tokens.get(pos++);
        }

        Object parseAndEvaluate() {
            Object value = parseComparison();
            if (peek().type() != TokenType.EOF) {
                throw new SandboxException("表达式存在多余内容: '" + peek().text() + "'");
            }
            return value;
        }

        // comparison := additive ( cmpOp additive )*
        private Object parseComparison() {
            Object left = parseAdditive();
            while (peek().type() == TokenType.OP && isCompareOp(peek().text())) {
                tick();
                String op = next().text();
                Object right = parseAdditive();
                double l = asNumber(left);
                double r = asNumber(right);
                left = switch (op) {
                    case "==" -> l == r;
                    case "!=" -> l != r;
                    case "<" -> l < r;
                    case "<=" -> l <= r;
                    case ">" -> l > r;
                    case ">=" -> l >= r;
                    default -> throw new SandboxException("未知比较运算符: " + op);
                };
            }
            return left;
        }

        private boolean isCompareOp(String op) {
            return op.equals("==") || op.equals("!=") || op.equals("<")
                    || op.equals("<=") || op.equals(">") || op.equals(">=");
        }

        // additive := multiplicative ( (+|-) multiplicative )*
        private Object parseAdditive() {
            double acc = asNumber(parseMultiplicative());
            while (peek().type() == TokenType.OP && (peek().text().equals("+") || peek().text().equals("-"))) {
                tick();
                String op = next().text();
                double rhs = asNumber(parseMultiplicative());
                acc = op.equals("+") ? acc + rhs : acc - rhs;
            }
            return acc;
        }

        // multiplicative := unary ( (*|/|%) unary )*
        private Object parseMultiplicative() {
            double acc = asNumber(parseUnary());
            while (peek().type() == TokenType.OP
                    && (peek().text().equals("*") || peek().text().equals("/") || peek().text().equals("%"))) {
                tick();
                String op = next().text();
                double rhs = asNumber(parseUnary());
                acc = switch (op) {
                    case "*" -> acc * rhs;
                    case "/" -> {
                        if (rhs == 0) throw new SandboxException("除零错误");
                        yield acc / rhs;
                    }
                    case "%" -> {
                        if (rhs == 0) throw new SandboxException("模零错误");
                        yield acc % rhs;
                    }
                    default -> throw new SandboxException("未知运算符: " + op);
                };
            }
            return acc;
        }

        // unary := (-|+) unary | primary
        private Object parseUnary() {
            if (peek().type() == TokenType.OP && (peek().text().equals("-") || peek().text().equals("+"))) {
                tick();
                String op = next().text();
                double v = asNumber(parseUnary());
                return op.equals("-") ? -v : v;
            }
            return parsePrimary();
        }

        // primary := NUMBER | bool | IDENT [ '(' args ')' ] | '(' comparison ')'
        private Object parsePrimary() {
            tick();
            Token t = peek();
            switch (t.type()) {
                case NUMBER -> {
                    next();
                    return t.number();
                }
                case LPAREN -> {
                    next();
                    Object v = parseComparison();
                    expect(TokenType.RPAREN, ")");
                    return v;
                }
                case IDENT -> {
                    next();
                    String name = t.text();
                    if (name.equalsIgnoreCase("true")) return 1.0;
                    if (name.equalsIgnoreCase("false")) return 0.0;
                    if (peek().type() == TokenType.LPAREN) {
                        return callFunction(name);
                    }
                    Double v = vars.get(name);
                    if (v == null) {
                        throw new SandboxException("未定义的变量: " + name);
                    }
                    return v;
                }
                default -> throw new SandboxException("意外的记号: '" + t.text() + "'");
            }
        }

        private Object callFunction(String name) {
            String key = name.toLowerCase();
            Integer arity = FUNCTIONS.get(key);
            if (arity == null) {
                throw new SandboxException("不支持的函数: " + name
                        + "（白名单: " + String.join(", ", FUNCTIONS.keySet()) + "）");
            }
            expect(TokenType.LPAREN, "(");
            List<Double> args = new ArrayList<>();
            if (peek().type() != TokenType.RPAREN) {
                args.add(asNumber(parseComparison()));
                while (peek().type() == TokenType.COMMA) {
                    next();
                    args.add(asNumber(parseComparison()));
                }
            }
            expect(TokenType.RPAREN, ")");

            if (arity == -1) {
                if (args.size() < 2) {
                    throw new SandboxException("函数 " + key + " 至少需要2个参数");
                }
            } else if (args.size() != arity) {
                throw new SandboxException("函数 " + key + " 需要 " + arity + " 个参数，实际 " + args.size());
            }

            tick();
            return switch (key) {
                case "abs" -> Math.abs(args.get(0));
                case "sqrt" -> {
                    if (args.get(0) < 0) throw new SandboxException("sqrt 参数不能为负");
                    yield Math.sqrt(args.get(0));
                }
                case "floor" -> Math.floor(args.get(0));
                case "ceil" -> Math.ceil(args.get(0));
                case "round" -> (double) Math.round(args.get(0));
                case "log", "ln" -> {
                    if (args.get(0) <= 0) throw new SandboxException("log 参数必须为正");
                    yield Math.log(args.get(0));
                }
                case "log10" -> {
                    if (args.get(0) <= 0) throw new SandboxException("log10 参数必须为正");
                    yield Math.log10(args.get(0));
                }
                case "sin" -> Math.sin(args.get(0));
                case "cos" -> Math.cos(args.get(0));
                case "tan" -> Math.tan(args.get(0));
                case "exp" -> Math.exp(args.get(0));
                case "pow" -> Math.pow(args.get(0), args.get(1));
                case "min" -> args.stream().min(Double::compareTo).orElseThrow();
                case "max" -> args.stream().max(Double::compareTo).orElseThrow();
                default -> throw new SandboxException("不支持的函数: " + key);
            };
        }

        private void expect(TokenType type, String what) {
            if (peek().type() != type) {
                throw new SandboxException("期望 " + what + "，实际 '" + peek().text() + "'");
            }
            next();
        }

        private double asNumber(Object v) {
            if (v instanceof Double d) {
                return d;
            }
            if (v instanceof Boolean b) {
                return b ? 1.0 : 0.0;
            }
            throw new SandboxException("类型错误：需要数值");
        }
    }

    /**
     * 沙箱内部异常（受控错误，作为求值失败原因返回，不向外抛出栈）
     */
    public static class SandboxException extends RuntimeException {
        public SandboxException(String message) {
            super(message);
        }
    }
}

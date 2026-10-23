import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class CalculatorServer {
    private static final int PORT = 8000;
    private static final int MAX_REQUEST_BYTES = 8192;
    private static final Map<String, String> OPERATOR_SYMBOLS = Map.of(
            "+", "+",
            "-", "−",
            "*", "×",
            "/", "÷"
    );

    private final Path projectDirectory;

    private CalculatorServer(Path projectDirectory) {
        this.projectDirectory = projectDirectory;
    }

    public static void main(String[] args) throws IOException {
        Path projectDirectory = Path.of(args.length > 0 ? args[0] : ".")
                .toAbsolutePath()
                .normalize();
        CalculatorServer calculator = new CalculatorServer(projectDirectory);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/", calculator::handleRequest);
        server.start();
        System.out.println("Calculator is running at http://localhost:" + PORT);
        System.out.println("Press Ctrl+C to stop the server.");
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (path.equals("/styles.css") && method.equals("GET")) {
            serveStylesheet(exchange);
            return;
        }
        if (!path.equals("/")) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        if (method.equals("GET")) {
            send(exchange, 200, "text/html; charset=utf-8", render(State.initial()));
            return;
        }
        if (!method.equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "GET, POST");
            send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
            return;
        }

        try {
            Map<String, String> fields = parseForm(exchange);
            State state = State.from(fields);
            send(exchange, 200, "text/html; charset=utf-8", render(applyInput(state, fields)));
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, "text/plain; charset=utf-8", exception.getMessage());
        }
    }

    private void serveStylesheet(HttpExchange exchange) throws IOException {
        Path stylesheet = projectDirectory.resolve("styles.css").normalize();
        if (!stylesheet.startsWith(projectDirectory) || !Files.isRegularFile(stylesheet)) {
            send(exchange, 404, "text/plain; charset=utf-8", "Stylesheet not found");
            return;
        }
        byte[] content = Files.readAllBytes(stylesheet);
        exchange.getResponseHeaders().set("Content-Type", "text/css; charset=utf-8");
        exchange.sendResponseHeaders(200, content.length);
        try (var output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Form submission is too large.");
        }

        String body = new String(bytes, StandardCharsets.UTF_8);
        Map<String, String> fields = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";
            fields.put(key, value);
        }
        return fields;
    }

    private static State applyInput(State state, Map<String, String> fields) {
        String theme = fields.getOrDefault("theme", state.theme());
        if (!theme.equals("dark") && !theme.equals("light")) {
            theme = state.theme();
        }

        String current = state.current();
        String stored = state.stored();
        String operator = state.operator();
        String expression = state.expression();
        boolean startNew = state.startNew();
        boolean justCalculated = state.justCalculated();

        if (fields.containsKey("theme")) {
            return new State(current, stored, operator, expression, startNew, justCalculated, theme);
        }

        if (fields.containsKey("digit")) {
            String digit = fields.get("digit");
            if (!digit.matches("\\d")) {
                throw new IllegalArgumentException("Invalid digit.");
            }
            if (current.equals("Error") || startNew || justCalculated) {
                current = digit;
                startNew = false;
                justCalculated = false;
            } else if (current.equals("0")) {
                current = digit;
            } else if (current.replace("-", "").length() < 14) {
                current += digit;
            }
        } else if (fields.containsKey("operation")) {
            String nextOperator = fields.get("operation");
            if (!OPERATOR_SYMBOLS.containsKey(nextOperator)) {
                throw new IllegalArgumentException("Invalid operator.");
            }
            if (!current.equals("Error")) {
                double input = parseNumber(current);
                if (!operator.isEmpty() && !startNew) {
                    current = formatResult(calculate(parseNumber(stored), input, operator));
                    stored = current.equals("Error") ? "" : current;
                } else {
                    stored = current;
                }
                operator = current.equals("Error") ? "" : nextOperator;
                expression = current.equals("Error")
                        ? ""
                        : current + " " + OPERATOR_SYMBOLS.get(nextOperator);
                startNew = true;
                justCalculated = false;
            }
        } else {
            String action = fields.get("action");
            if (action == null) {
                throw new IllegalArgumentException("No calculator action was submitted.");
            }
            switch (action) {
                case "clear" -> {
                    current = "0";
                    stored = "";
                    operator = "";
                    expression = "";
                    startNew = false;
                    justCalculated = false;
                }
                case "decimal" -> {
                    if (current.equals("Error") || startNew || justCalculated) {
                        current = "0.";
                        startNew = false;
                        justCalculated = false;
                    } else if (!current.contains(".")) {
                        current += ".";
                    }
                }
                case "sign" -> {
                    if (!current.equals("Error") && parseNumber(current) != 0) {
                        current = current.startsWith("-") ? current.substring(1) : "-" + current;
                    }
                }
                case "percent" -> {
                    if (!current.equals("Error")) {
                        current = formatResult(parseNumber(current) / 100);
                    }
                }
                case "equals" -> {
                    if (!operator.isEmpty() && !stored.isEmpty() && !current.equals("Error")) {
                        String left = stored;
                        String right = current;
                        current = formatResult(calculate(parseNumber(left), parseNumber(right), operator));
                        expression = left + " " + OPERATOR_SYMBOLS.get(operator) + " " + right + " =";
                        stored = "";
                        operator = "";
                        startNew = true;
                        justCalculated = true;
                    }
                }
                default -> throw new IllegalArgumentException("Invalid calculator action.");
            }
        }

        return new State(current, stored, operator, expression, startNew, justCalculated, theme);
    }

    private static double parseNumber(String value) {
        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("Invalid calculator value.");
            }
            return number;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid calculator value.");
        }
    }

    private static double calculate(double left, double right, String operator) {
        return switch (operator) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> right == 0 ? Double.NaN : left / right;
            default -> throw new IllegalArgumentException("Invalid operator.");
        };
    }

    private static String formatResult(double value) {
        if (!Double.isFinite(value)) {
            return "Error";
        }
        return BigDecimal.valueOf(value)
                .round(new MathContext(12, RoundingMode.HALF_UP))
                .stripTrailingZeros()
                .toPlainString();
    }

    private String render(State state) throws IOException {
        String html = Files.readString(projectDirectory.resolve("index.html"), StandardCharsets.UTF_8);
        boolean darkTheme = state.theme().equals("dark");
        return html
                .replace("@@THEME@@", escapeHtml(state.theme()))
                .replace("@@CURRENT@@", escapeHtml(state.current()))
                .replace("@@STORED@@", escapeHtml(state.stored()))
                .replace("@@OPERATOR@@", escapeHtml(state.operator()))
                .replace("@@START_NEW@@", Boolean.toString(state.startNew()))
                .replace("@@JUST_CALCULATED@@", Boolean.toString(state.justCalculated()))
                .replace("@@EXPRESSION@@", escapeHtml(state.expression()))
                .replace("@@DARK_ACTIVE@@", darkTheme ? "is-active" : "")
                .replace("@@LIGHT_ACTIVE@@", darkTheme ? "" : "is-active")
                .replace("@@DARK_PRESSED@@", Boolean.toString(darkTheme))
                .replace("@@LIGHT_PRESSED@@", Boolean.toString(!darkTheme));
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private record State(
            String current,
            String stored,
            String operator,
            String expression,
            boolean startNew,
            boolean justCalculated,
            String theme
    ) {
        private static State initial() {
            return new State("0", "", "", "", false, false, "dark");
        }

        private static State from(Map<String, String> fields) {
            String current = fields.getOrDefault("current", "0");
            if (!current.equals("Error") && !current.matches("-?\\d+(\\.\\d*)?")) {
                throw new IllegalArgumentException("Invalid calculator value.");
            }
            String stored = fields.getOrDefault("stored", "");
            if (!stored.isEmpty()) {
                parseNumber(stored);
            }
            String operator = fields.getOrDefault("pendingOperator", "");
            if (!operator.isEmpty() && !OPERATOR_SYMBOLS.containsKey(operator)) {
                throw new IllegalArgumentException("Invalid operator.");
            }
            String theme = fields.getOrDefault("currentTheme", "dark");
            if (!theme.equals("dark") && !theme.equals("light")) {
                throw new IllegalArgumentException("Invalid theme.");
            }
            return new State(
                    current,
                    stored,
                    operator,
                    fields.getOrDefault("expression", ""),
                    Boolean.parseBoolean(fields.getOrDefault("startNew", "false")),
                    Boolean.parseBoolean(fields.getOrDefault("justCalculated", "false")),
                    theme
            );
        }
    }
}

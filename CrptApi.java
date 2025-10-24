import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    private static final URI BASE = URI.create("https://ismp.crpt.ru");
    private static final String PATH_CREATE = "/api/v3/lk/documents/create";

    private final HttpClient http = HttpClient.newHttpClient();
    private final RateLimiter limiter;
    private volatile Duration timeout = Duration.ofSeconds(30);
    private volatile String productGroup;
    private volatile String bearerToken;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (timeUnit == null || requestLimit <= 0)
            throw new IllegalArgumentException("Неверные параметры лимита");
        this.limiter = new RateLimiter(timeUnit.toMillis(1), requestLimit);
    }

    public void setProductGroup(String productGroup) {
        this.productGroup = Objects.requireNonNull(productGroup, "productGroup");
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = Objects.requireNonNull(bearerToken, "bearerToken");
    }

    public void setTimeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public String createDocument(String documentJson, String signature)
            throws IOException, InterruptedException {

        if (documentJson == null || documentJson.isEmpty())
            throw new IllegalArgumentException("Документ не задан");
        if (signature == null || signature.isEmpty())
            throw new IllegalArgumentException("Подпись не задана");
        if (productGroup == null || productGroup.isEmpty())
            throw new IllegalStateException("productGroup не установлен");
        if (bearerToken == null || bearerToken.isEmpty())
            throw new IllegalStateException("bearerToken не установлен. Укажи токен методом setBearerToken().");

        limiter.acquire();

        String productDocumentB64 = Base64.getEncoder()
                .encodeToString(documentJson.getBytes(StandardCharsets.UTF_8));

        String bodyJson = "{"
                + "\"product_document\":\"" + escapeJson(productDocumentB64) + "\","
                + "\"document_format\":\"MANUAL\","
                + "\"type\":\"LP_INTRODUCE_GOODS\","
                + "\"signature\":\"" + escapeJson(signature) + "\""
                + "}";

        String pgEncoded = URLEncoder.encode(productGroup, StandardCharsets.UTF_8);
        URI uri = BASE.resolve(PATH_CREATE + "?pg=" + pgEncoded);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();

        if (code / 100 == 2) {
            String body = resp.body();
            int idx = body.indexOf("\"value\"");
            if (idx != -1) {
                int start = body.indexOf('"', idx + 7);
                int end = body.indexOf('"', start + 1);
                if (start != -1 && end != -1) {
                    return body.substring(start + 1, end);
                }
            }
            return "Успешный ответ, но без поля value: " + body;
        } else if (code == 401) {
            throw new IOException("Ошибка авторизации: неверный или просроченный токен.");
        } else if (code == 500 && resp.body().contains("Ошибка получения токена")) {
            throw new IOException("Сервер Честного знака не принял токен. Проверь bearerToken().");
        } else {
            throw new IOException("HTTP " + code + ": " + resp.body());
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class RateLimiter {
        private final long windowMs;
        private final int limit;
        private final Deque<Long> times = new ArrayDeque<>();

        RateLimiter(long windowMs, int limit) {
            this.windowMs = windowMs;
            this.limit = limit;
        }

        synchronized void acquire() throws InterruptedException {
            for (;;) {
                long now = System.currentTimeMillis();
                while (!times.isEmpty() && times.peekFirst() <= now - windowMs) {
                    times.pollFirst();
                }
                if (times.size() < limit) {
                    times.addLast(now);
                    notifyAll();
                    return;
                }
                long waitMs = (times.peekFirst() + windowMs) - now;
                if (waitMs > 0) wait(waitMs);
            }
        }
    }

    //пример запуска
    public static void main(String[] args) {
        try {
            CrptApi api = new CrptApi(TimeUnit.MINUTES, 5);
            api.setProductGroup("milk");
            api.setBearerToken("SomeToken");

            String jsonDocument = "{"
                    + "\"doc_id\":\"12345\","
                    + "\"doc_type\":\"LP_INTRODUCE_GOODS\","
                    + "\"participant_inn\":\"7700000000\","
                    + "\"production_type\":\"OWN_PRODUCTION\""
                    + "}";

            String fakeSignature = "QmFzZTY0U2lnbmF0dXJl";

            String result = api.createDocument(jsonDocument, fakeSignature);
            System.out.println("Результат: " + result);
        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
        }
    }
}

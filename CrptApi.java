import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class CrptApi {

    private static final URI BASE = URI.create("https://ismp.crpt.ru");
    private static final String PATH_CREATE = "/api/v3/lk/documents/create";
    private static final String HDR_CONTENT = "Content-Type";
    private static final String HDR_AUTH = "Authorization";
    private static final String BEARER = "Bearer ";
    private static final String JSON = "application/json";
    private static final String DOC_FORMAT = "MANUAL";
    private static final String DOC_TYPE = "LP_INTRODUCE_GOODS";

    private final HttpClient client;
    private final String token;

    private final Semaphore limiter;
    private final int maxReq;
    private final long periodMillis;

    private final ScheduledExecutorService refill;
    private final ObjectMapper mapper;

    public CrptApi(String token, TimeUnit period, int maxReq) {
        this.token = Objects.requireNonNull(token, "token");
        if (maxReq <= 0) throw new IllegalArgumentException("maxReq must be positive");
        this.maxReq = maxReq;
        this.periodMillis = period.toMillis(1);
        this.limiter = new Semaphore(maxReq, true);
        this.client = HttpClient.newHttpClient();

        this.mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "crptapi-refill");
            t.setDaemon(true);
            return t;
        };
        this.refill = Executors.newScheduledThreadPool(1, tf);
        startRefill();
    }

    private void startRefill() {
        refill.scheduleAtFixedRate(() -> {
            int need = maxReq - limiter.availablePermits();
            if (need > 0) limiter.release(need);
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    public ApiReply createRuIntroDocument(Doc doc, String signature, String productGroup)
            throws IOException, InterruptedException {
        Objects.requireNonNull(doc);
        Objects.requireNonNull(signature);
        Objects.requireNonNull(productGroup);

        limiter.acquire();

        String json = mapper.writeValueAsString(doc);
        String encoded = Base64.getEncoder().encodeToString(json.getBytes());

        CreatePayload payload = new CreatePayload();
        payload.document_format = DOC_FORMAT;
        payload.product_document = encoded;
        payload.product_group = productGroup;
        payload.signature = signature;
        payload.type = DOC_TYPE;

        String body = mapper.writeValueAsString(payload);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(BASE.resolve(PATH_CREATE))
                .header(HDR_CONTENT, JSON)
                .header(HDR_AUTH, BEARER + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return mapper.readValue(resp.body(), ApiReply.class);
        } else {
            ApiReply err = new ApiReply();
            err.code = String.valueOf(resp.statusCode());
            err.error_message = resp.body();
            return err;
        }
    }

    public void shutdown() {
        refill.shutdownNow();
    }

    private static class CreatePayload {
        public String document_format;
        public String product_document;
        public String product_group;
        public String signature;
        public String type;
    }

    public static class ApiReply {
        public String value;
        public String code;
        public String error_message;
        public String description;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Doc {
        public Meta description;
        public String doc_id;
        public String doc_status;
        public String doc_type;
        public Boolean importRequest;
        public String owner_inn;
        public String participant_inn;
        public String producer_inn;
        public String production_date;
        public String production_type;
        public List<Item> products;
        public String reg_date;
        public String reg_number;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Meta {
        public String participantInn;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Item {
        public String certificate_document;
        public String certificate_document_date;
        public String certificate_document_number;
        public String owner_inn;
        public String producer_inn;
        public String production_date;
        public String tnved_code;
        public String uit_code;
        public String uitu_code;
    }
}

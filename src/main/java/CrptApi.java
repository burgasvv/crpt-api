import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public record CrptApi(TimeUnit time, int requestLimit) {

    private static final String AUTHENTICATION_URL = "https://ismp.crpt.ru/api/v3/auth/cert/key";
    private static final String TOKEN_URL = "https://ismp.crpt.ru/api/v3/auth/cert/";
    private static final String CREATE_DOCUMENT_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";

    private static AtomicInteger requestAmount;
    private static AtomicLong timeForRequest = new AtomicLong(0);

    public CrptApi(TimeUnit time, int requestLimit) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Отрицательное или нулевое значение лимита запросов");
        }
        this.time = time;
        this.requestLimit = requestLimit;
        requestAmount = new AtomicInteger(this.requestLimit);
    }

    public static void main(String[] args) {
        CrptApi crptApi = new CrptApi(TimeUnit.SECONDS, 3);
        Document document = getDocument();
        Stream.generate(
                () -> new Thread(
                        () -> {
                            try {
                                String response = crptApi.introduceGoods(
                                        document, UUID.randomUUID().toString().replaceAll("-", "")
                                );
                                System.out.println(response);

                            } catch (IOException | InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                )
        )
                .limit(15)
                .forEach(Thread::start);
    }

    public synchronized String introduceGoods(final Document document, final String signature) throws IOException, InterruptedException {
        long startRequestTime = System.currentTimeMillis();

        JSONObject documentJson = new JSONObject();
        JSONObject descriptionJson = new JSONObject();
        descriptionJson.put("participantInn", document.description.participantInn);
        documentJson.put("description", descriptionJson.toString());
        documentJson.put("doc_id", document.docId);
        documentJson.put("doc_status", document.docStatus);
        documentJson.put("doc_type", document.docType);
        documentJson.put("importRequest", document.importRequest);
        documentJson.put("owner_inn", document.ownerInn);
        documentJson.put("participant_inn", document.participantInn);
        documentJson.put("producer_inn", document.producerInn);
        documentJson.put("production_date", document.productionDate);
        documentJson.put("production_type", document.productionType);

        JSONArray listProductsJson = new JSONArray();
        document.products.forEach(
                product -> {
                    JSONObject productsJson = new JSONObject();
                    productsJson.put("certificate_document", product.certificateDocument);
                    productsJson.put("certificate_document_date", product.certificateDocumentDate);
                    productsJson.put("certificate_document_number", product.certificateDocumentNumber);
                    productsJson.put("owner_inn", product.ownerInn);
                    productsJson.put("producer_inn", product.producerInn);
                    productsJson.put("production_date", product.productionDate);
                    productsJson.put("tnved_code", product.tnvedCode);
                    productsJson.put("uit_code", product.uitCode);
                    productsJson.put("uitu_code", product.uituCode);
                    listProductsJson.put(productsJson);
                }
        );

        documentJson.put("products", listProductsJson);
        documentJson.put("reg_date", document.regDate);
        documentJson.put("reg_number", document.regNumber);

        HttpClient httpClient = HttpClient.newHttpClient();

        HttpRequest authDataRequest = HttpRequest.newBuilder()
                .uri(URI.create(AUTHENTICATION_URL))
                .GET()
                .header("Content-Type", "application/json")
                .build();
        String authData = httpClient.send(authDataRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();

        HttpRequest authTokenRequest = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .POST(HttpRequest.BodyPublishers.ofString(authData))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Content-Encoding", "Base64")
                .header("signature", signature)
                .build();
        @SuppressWarnings("unused") String authToken = httpClient.send(authTokenRequest, HttpResponse.BodyHandlers.ofString()).body();

        HttpRequest createDocumentRequest = HttpRequest.newBuilder()
                .uri(URI.create(CREATE_DOCUMENT_URL))
                .POST(HttpRequest.BodyPublishers.ofString(documentJson.toString()))
                .header("Content-Type", "application/json")
                .build();

        String createDocumentResponse = httpClient.send(createDocumentRequest, HttpResponse.BodyHandlers.ofString()).body();

        requestAmount.decrementAndGet();

        long endRequestTime = System.currentTimeMillis();
        long resultRequestTime = endRequestTime - startRequestTime;
        long commonRequestsTime = timeForRequest.addAndGet(resultRequestTime);

        if (commonRequestsTime > getTime()) {
            System.out.println("Превышение времени выполнения запросов: " + commonRequestsTime);

        } else {
            System.out.println("Общее время выполненных запросов: " + commonRequestsTime);
        }

        if (
                (requestAmount.get() < 1 || getTime() <= commonRequestsTime) ||
                (requestAmount.get() < 1 && getTime() <= commonRequestsTime)
        ) {
            System.out.println("Блокировка");
            Thread.sleep(getTime());
            requestAmount = new AtomicInteger(this.requestLimit);
            timeForRequest = new AtomicLong(0);
        }
        return createDocumentResponse;
    }

    private static Document getDocument() {
        Document.Description description = new Document.Description("description");
        Document.Product firstProduct = new Document.Product(
                Document.Product.CertificateType.CONFORMITY_CERTIFICATE,
                "2025-10-15", "docNumber", "ownerInn",
                "producerInn", "2025-10-15",
                "tnvedCode", "uitCode", "uituCode"
        );
        return new Document(
                description, "docId", "docStatus", "docType",
                "true", "ownerInn", "participantInn", "producerInn",
                "2025-10-15", "productionType", List.of(firstProduct), "2025-10-15", "regNumber"
        );
    }

    private long getTime() {
        return switch (this.time) {
            case SECONDS, MINUTES, HOURS, DAYS -> time().toChronoUnit().getDuration().toMillis();
            default -> throw new IllegalArgumentException("Неверная единица измерения времени");
        };
    }

    public record Document(Description description, String docId, String docStatus, String docType,
                           String importRequest, String ownerInn, String participantInn, String producerInn,
                           String productionDate, String productionType, List<Product> products, String regDate,
                           String regNumber) {

        public record Description(String participantInn) {}

        public record Product(CertificateType certificateDocument, String certificateDocumentDate,
                              String certificateDocumentNumber, String ownerInn, String producerInn,
                              String productionDate, String tnvedCode, String uitCode, String uituCode) {

            public enum CertificateType {
                CONFORMITY_CERTIFICATE, CONFORMITY_DECLARATION
            }
        }
    }
}

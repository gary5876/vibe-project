package dbdr.domain.core.ocr.service;

import dbdr.domain.core.ocr.entity.OcrData;
import dbdr.domain.core.ocr.repository.OcrRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.URL;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;

@Service
@Slf4j
@RequiredArgsConstructor
public class OcrService {

    private final OcrRepository ocrRepository;

    // WebClient는 그대로 사용 (Bean 주입으로 바꿔도 됨)
    private final WebClient webClient = WebClient.builder().build();

    //@Value("${clova-ocr.api-url}")
    private String apiUrl = "https://21cst6kejo.apigw.ntruss.com/custom/v1/48293/bdb0e882a01f54b3a84a0d55d1ff2a6097a15bc5b774cb4a73f3458cacd28b46/general";

    //@Value("${clova-ocr.secret-key}")
    private String secretKey = "WHN1aU14aEFUVGhkd1Z4T0p5T1JTWlhzSk5QSHZMV2I=";

    /**
     * OCR 요청 메서드
     * @param imageUrl 클로바가 직접 접근 가능한 이미지 URL (S3 퍼블릭/프리사인 URL)
     * @param objectKey ocr_data 테이블의 object_key
     */
    @Transactional
    public Mono<String> performOcr(URL imageUrl, String objectKey) {
        return sendOcrRequest(imageUrl)
            .flatMap(response -> {
                String extractedText = extractTableText(response);
                updateOcrData(objectKey, extractedText);
                return Mono.just(extractedText);
            })
            .doOnError(error -> log.error("OCR 요청 실패: {}", error.getMessage()))
            .onErrorResume(WebClientResponseException.class, ex ->
                Mono.error(new RuntimeException("클로바 OCR 요청 실패: " + ex.getMessage()))
            );
    }

    /**
     * 클로바 OCR API에 요청을 보내는 메서드
     * - curl로 성공했던 형태와 100% 동일하게 맞춤
     */
    private Mono<String> sendOcrRequest(URL imageUrl) {

        // 🔹 curl에서 사용했던 JSON 포맷과 동일하게 구성
        Map<String, Object> requestBody = Map.of(
            "version", "V2",
            "requestId", "dbdr-" + System.currentTimeMillis(),
            "timestamp", System.currentTimeMillis(),
            "images", new Object[] {
                Map.of(
                    "format", "jpg",              // curl에서 사용했던 것과 동일
                    "name", "chart-test",        // 아무 이름이나 가능
                    "url", imageUrl.toString()   // S3 퍼블릭/프리사인 URL
                    // "type", "TABLE"  // ❌ general 엔드포인트에서는 굳이 안 넣어도 됨
                )
            }
        );

        log.info("CLOVA OCR REQUEST BODY: {}", requestBody);

        return webClient.post()
            .uri(apiUrl)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header("X-OCR-SECRET", secretKey)
            .bodyValue(requestBody)
            .retrieve()
            // 🔹 4xx/5xx 응답 시, 클로바 에러 바디까지 같이 보기 위해 처리
            .onStatus(HttpStatusCode::isError, clientResponse ->
                clientResponse.bodyToMono(String.class)
                    .flatMap(body -> {
                        log.error("CLOVA OCR ERROR BODY: {}", body);
                        return Mono.error(new RuntimeException("클로바 OCR 요청 실패: " + body));
                    })
            )
            .bodyToMono(String.class);
    }

    /**
     * JSON 응답에서 표 데이터를 추출하는 메서드
     * - 현재는 images[0].fields 의 inferText들을 공백으로 이어붙임
     */
    private String extractTableText(String response) {
        StringBuilder tableText = new StringBuilder();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode root = objectMapper.readTree(response);
            JsonNode fields = root.path("images").get(0).path("fields");

            if (fields.isMissingNode() || fields.isEmpty()) {
                log.warn("OCR 응답에 'fields' 데이터가 없습니다.");
                return "데이터가 없습니다.";
            }

            for (JsonNode field : fields) {
                String inferText = field.path("inferText").asText();
                tableText.append(inferText).append(" ");
            }
        } catch (Exception e) {
            log.error("데이터 추출 중 오류 발생: {}", e.getMessage());
        }
        return tableText.toString().trim();
    }

    /**
     * OCR 데이터 최초 생성
     * - 이미지 URL 저장 시 호출
     */
    @Transactional
    public void createOcrDate(String objectKey) {
        OcrData ocrData = new OcrData();
        ocrData.setObjectKey(objectKey);
        ocrRepository.save(ocrData);
        log.info("새로운 OCR 데이터 저장: {}", ocrData);
    }

    /**
     * OCR 결과 업데이트
     */
    @Transactional
    public void updateOcrData(String objectKey, String ocrResult) {
        OcrData ocrData = ocrRepository.findByObjectKey(objectKey);
        ocrData.setOcrResult(ocrResult);
        ocrRepository.save(ocrData);
        log.info("기존 OCR 데이터 업데이트: {}", ocrData);
    }
}

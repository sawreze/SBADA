package com.sbada.image;

import java.net.URI;
import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * ┌───────────────────────────────────────────┐
 * │ 사진 API   (기본 주소: /api/images)          │
 * └───────────────────────────────────────────┘
 *  POST /api/images        🔒  사진 올리기 → 201 + 사진 번호(id)
 *  GET  /api/images/{id}   공개 사진 파일(바이트) 내려받기
 *
 *  물건 등록 순서: ① POST /api/images 로 사진을 먼저 올려 id 를 받고
 *                 ② POST /api/ai/analyze 에 그 id 로 가격 분석
 *                 ③ POST /api/items 에 imageIds:[id] 와 analysisId 로 등록 → 이때 사진이 물건에 붙는다.
 *
 * [프로그래밍 설명 — 파일 업로드]
 *  - 파일은 JSON 이 아니라 multipart/form-data 형식으로 보낸다(브라우저 <form enctype="multipart/form-data"> 와 같은 방식).
 *    요청 본문이 여러 '부분(part)'으로 나뉘고, 각 부분에 이름(file)과 파일 바이트가 들어간다.
 *  - consumes = MULTIPART_FORM_DATA_VALUE: Content-Type 이 multipart/form-data 인 요청만 이 메서드로 온다.
 *  - @RequestParam("file") MultipartFile: 'file' 이라는 이름의 부분을 파일 객체로 받는다.
 *    (Postman: Body → form-data → key 'file', 형식 File)
 */
@RestController
@RequestMapping("/api/images")
public class ImageController {

    private final ImageService service;

    public ImageController(ImageService service) {
        this.service = service;
    }

    /**
     * ■ 사진 올리기   POST /api/images   (🔒)
     *   multipart/form-data 의 file 항목에 사진을 넣어 보낸다(7장 9.2).
     *
     * 요청: Content-Type: multipart/form-data
     *       file = (PNG 또는 JPEG 파일, 5MB 이하, 가로·세로 8000px 이하)
     *
     * 응답 201 — ImageService.StoredImage  (Location 헤더 = /api/images/{id})
     *   {"id": 33, "url": "/api/images/33", "contentType": "image/jpeg", "size": 42814, "width": 800, "height": 800}
     *
     * 오류
     *   400 FILE_EMPTY / INVALID_MULTIPART(형식이 multipart 가 아님) / IMAGE_TOO_BIG(8000px 초과)
     *   401 AUTH_REQUIRED
     *   413 FILE_TOO_LARGE(5MB 초과)
     *   415 UNSUPPORTED_IMAGE(PNG·JPEG 가 아니거나 깨진 파일)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageService.StoredImage> upload(@RequestParam("file") MultipartFile file,
                                                           Authentication auth) {
        // 누가 올렸는지(uploader)를 함께 저장 → 나중에 '내가 올린 사진만' 분석·등록에 쓸 수 있게 검사한다
        ImageService.StoredImage stored = service.store((Long) auth.getPrincipal(), file);
        return ResponseEntity.created(URI.create(stored.url())).body(stored);
    }

    /**
     * ■ 사진 보기   GET /api/images/{id}   (공개)
     *   상품 사진은 장터에 공개되는 정보이므로 로그인 없이 볼 수 있다.
     *   (앱의 Image.network, 지도 WebView 의 <img src> 가 이 주소를 그대로 부른다)
     *
     * 응답 200: 본문 = 사진 바이트(JSON 아님), Content-Type = image/jpeg 또는 image/png
     *           Cache-Control: max-age=604800, public → 앱·브라우저가 7일 동안 다시 받지 않고 저장본을 쓴다.
     * 오류: 404 IMAGE_NOT_FOUND / IMAGE_FILE_MISSING, 400 INVALID_PARAMETER(id 가 숫자가 아님)
     *
     * @PathVariable long id: 주소의 {id} 자리 값을 파라미터로 받는다(/api/images/33 → id = 33).
     * ResponseEntity<byte[]>: 본문을 바이트 배열 그대로 보낸다(Jackson JSON 변환을 거치지 않음).
     */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable long id) {
        ImageService.ImageBytes image = service.load(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(image.bytes());
    }
}

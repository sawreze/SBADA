package com.sbada.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.sbada.common.ApiException;
import com.sbada.user.AppUser;
import com.sbada.user.AppUserRepository;

/**
 * 사진 저장(7장 9.3 기준을 따름).
 *  - 크기: 파일당 5 MiB 이하 (413)
 *  - 형식: 파일 시작 바이트(시그니처)로 PNG/JPEG를 판별. 확장자·선언 MIME만 믿지 않는다 (415)
 *  - 실제 이미지 해석이 되는지, 가로·세로 8000px 이하인지 확인
 *  - 저장 이름은 UUID. 원래 이름은 메타데이터로만 둔다(../ 경로 공격 방지)
 *
 * [프로그래밍 설명]
 *  - 파일 바이트는 디스크(data/uploads/UUID.jpg)에, 정보(누가·언제·크기)는 DB(item_images)에 나눠 저장한다.
 *    DB 에 큰 바이트를 넣으면 DB 가 무거워지고 백업·조회가 느려지기 때문이다.
 *  - 클라이언트가 보낸 '파일 이름'과 'Content-Type' 은 얼마든지 속일 수 있다(악성 파일을 photo.jpg 로 이름만 바꾸기).
 *    그래서 실제 바이트의 첫 부분(매직 넘버)을 보고, ImageIO 로 실제로 그림으로 읽히는지까지 확인한다.
 *  - 경로 공격(Path Traversal): 이름에 "../../windows/…" 를 넣어 다른 폴더에 쓰려는 공격.
 *    저장 이름을 서버가 UUID 로 새로 만들고, 결과 경로가 업로드 폴더 안인지(startsWith) 한 번 더 확인한다.
 */
@Service
public class ImageService {

    public static final long MAX_BYTES = 5L * 1024 * 1024; // 5 MiB (5L: long 계산으로 int 범위 넘침 방지)
    private static final int MAX_SIDE = 8000;

    private final ItemImageRepository images;
    private final AppUserRepository users;
    private final Path uploadDir;

    public ImageService(ItemImageRepository images, AppUserRepository users,
                        @Value("${sbada.upload.dir}") String uploadDir) {
        this.images = images;
        this.users = users;
        // 상대 경로(./data/uploads)를 절대 경로로 바꾸고 . / .. 를 정리해 둔다
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    /** POST /api/images 응답 모양 */
    public record StoredImage(long id, String url, String contentType, long size, int width, int height) {
        static StoredImage from(ItemImage i) {
            return new StoredImage(i.getId(), i.url(), i.getContentType(), i.getSize(), i.getWidth(), i.getHeight());
        }
    }

    /** 업로드된 파일(MultipartFile)을 검사·저장한다 — ImageController.upload 가 부른다 */
    @Transactional
    public StoredImage store(long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_EMPTY", "사진 파일이 비어 있습니다.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(413, "FILE_TOO_LARGE", "사진은 5MB 이하만 올릴 수 있습니다.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("FILE_READ_FAILED", "사진을 읽지 못했습니다.");
        }
        return storeBytes(userId, bytes, file.getOriginalFilename());
    }

    /** 바이트 배열을 검사·저장한다(업로드 외에 테스트·시연 데이터에서도 쓸 수 있게 분리) */
    @Transactional
    public StoredImage storeBytes(long userId, byte[] bytes, String originalName) {
        // ① 매직 넘버로 형식 판별 → PNG/JPEG 가 아니면 415 Unsupported Media Type
        String contentType = detectType(bytes);
        if (contentType == null) {
            throw new ApiException(415, "UNSUPPORTED_IMAGE", "PNG 또는 JPEG 사진만 올릴 수 있습니다.");
        }
        // ② 실제로 그림으로 해석되는지(앞부분만 흉내 낸 가짜 파일 거르기)
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            decoded = null;
        }
        if (decoded == null) {
            throw new ApiException(415, "UNSUPPORTED_IMAGE", "사진을 해석할 수 없습니다.");
        }
        // ③ 너무 큰 해상도(메모리 폭탄) 거절
        if (decoded.getWidth() > MAX_SIDE || decoded.getHeight() > MAX_SIDE) {
            throw ApiException.badRequest("IMAGE_TOO_BIG", "사진의 가로·세로는 8000픽셀 이하여야 합니다.");
        }
        AppUser uploader = users.findById(userId)
                .orElseThrow(() -> new ApiException(401, "TOKEN_INVALID", "사용자를 확인할 수 없습니다."));
        // ④ 저장 이름은 서버가 정한다: 예) 3f2a9c1e-….jpg  (UUID = 겹칠 확률이 사실상 0인 무작위 이름)
        String storedName = UUID.randomUUID() + (contentType.equals("image/png") ? ".png" : ".jpg");
        Path target = uploadDir.resolve(storedName).normalize();
        if (!target.startsWith(uploadDir)) { // 이중 안전장치: 업로드 폴더 밖이면 거절
            throw ApiException.badRequest("INVALID_PATH", "잘못된 저장 경로입니다.");
        }
        // ⑤ 디스크에 파일 쓰기
        try {
            Files.createDirectories(uploadDir);
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("사진 저장 실패", e);
        }
        // ⑥ 원래 이름은 참고용으로만 저장: 경로 구분자(\, /)를 _ 로 바꾸고 120자로 자른다
        String safeName = originalName == null ? null
                : originalName.replaceAll("[\\\\/]", "_").substring(0, Math.min(originalName.length(), 120));
        // ⑦ DB 에 메타데이터 INSERT. 아직 물건(item)에는 붙지 않은 상태(item_id = null)
        ItemImage saved = images.save(new ItemImage(uploader, storedName, safeName, contentType,
                bytes.length, decoded.getWidth(), decoded.getHeight()));
        return StoredImage.from(saved);
    }

    /** 사진 바이트 + 형식 (GET /api/images/{id} 응답에 쓴다) */
    public record ImageBytes(byte[] bytes, String contentType) {
    }

    @Transactional(readOnly = true)
    public ImageBytes load(long imageId) {
        ItemImage image = require(imageId);
        try {
            return new ImageBytes(Files.readAllBytes(uploadDir.resolve(image.getStoredName())), image.getContentType());
        } catch (IOException e) {
            // DB 행은 있는데 파일이 지워진 경우
            throw ApiException.notFound("IMAGE_FILE_MISSING", "사진 파일을 찾을 수 없습니다.");
        }
    }

    /** 사진 행을 찾고, 없으면 404. AI 분석·물건 등록에서도 쓴다. */
    @Transactional(readOnly = true)
    public ItemImage require(long imageId) {
        return images.findById(imageId)
                .orElseThrow(() -> ApiException.notFound("IMAGE_NOT_FOUND", "사진을 찾을 수 없습니다."));
    }

    /**
     * 파일 시그니처(매직 바이트)로 실제 형식을 판별한다.
     *  PNG : 89 50 4E 47 ('\x89' 'P' 'N' 'G') 로 시작
     *  JPEG: FF D8 FF 로 시작
     * Java 의 byte 는 -128~127 이라 0x89 같은 값은 음수로 저장된다 → (b & 0xFF) 로 0~255 정수로 바꿔 비교한다.
     */
    static String detectType(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return null;
    }
}

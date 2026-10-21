import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스바다 Gemma QLoRA 학습 데이터 만들기(Python 없이 Java 로 실행).
 *   java -Dfile.encoding=UTF-8 training\MakeDataset.java
 * 결과: training/data/train.jsonl, training/data/eval.jsonl  (한 줄 = {"task":..., "messages":[user, assistant]})
 *
 * 앱 서버가 실제로 보내는 질문과 '글자 하나까지' 같은 형식으로 만든다:
 *  - price      : AiPrompt.build(hint) 의 질문 → JSON(title · category · condition · price_krw …)
 *  - moderation : ModerationService.prompt(title, description) 의 질문 → {"allowed","category","reason"}
 * 서버 쪽 프롬프트 문장을 바꾸면 아래 PRICE_* / moderationPrompt 도 같이 바꿔야 한다.
 *
 * 데이터 출처: db/migration/V4__replace_demo_items.sql 의 상품 50개(가격 · 상태 · 설명)를 읽어,
 * 상태를 바꾼 변형(감가율 적용)까지 만들어 적은 수로도 '상태에 따라 값이 달라진다'를 배우게 한다.
 * 사진은 넣지 않는다(텍스트 힌트만) — 출력 형식 · 원화 시세 감각 · 검사 기준을 맞추는 것이 목적.
 */
public class MakeDataset {

    static final String[][] CATEGORIES = {
            {"DIGITAL", "디지털기기"}, {"APPLIANCE", "생활가전"}, {"FURNITURE", "가구/인테리어"}, {"KIDS", "유아동"},
            {"FASHION", "의류"}, {"ACCESSORY", "잡화"}, {"BEAUTY", "뷰티/미용"}, {"SPORTS", "스포츠/레저"},
            {"HOBBY", "취미/게임/음반"}, {"BOOK", "도서"}, {"TICKET", "티켓/교환권"}, {"PET", "반려동물용품"},
            {"PLANT", "식물"}, {"ETC", "기타"}};
    static final String[][] CONDITIONS = {
            {"NEW", "새 상품"}, {"LIKE_NEW", "거의 새것"}, {"GOOD", "사용감 적음"}, {"FAIR", "사용감 많음"}, {"POOR", "고장/부품용"}};
    static final Map<String, Double> FACTOR = Map.of("NEW", 1.00, "LIKE_NEW", 0.85, "GOOD", 0.70, "FAIR", 0.50, "POOR", 0.25);

    record Item(String title, String description, String category, String condition, int price, int min, int max,
                double confidence) {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path sql = root.resolve("sbada-server/src/main/resources/db/migration/V4__replace_demo_items.sql");
        List<Item> items = parse(Files.readString(sql, StandardCharsets.UTF_8));
        if (items.size() != 50) {
            throw new IllegalStateException("V4 상품을 50개 읽어야 하는데 " + items.size() + "개");
        }
        List<String> rows = new ArrayList<>();
        for (Item it : items) {
            rows.add(price(it, it.condition(), true));                 // 원래 상태(설명 · 이름 힌트 포함)
            rows.add(price(it, neighbor(it.condition()), false));      // 상태만 다른 변형(감가율 적용)
        }
        for (Item it : items) {                                        // 정상 물건 → 통과
            rows.add(moderation(it.title(), it.description(), true, "OK", ""));
        }
        String[][] bad = {
                {"씨발 헤드폰 팝니다", "상태 좋아요", "PROFANITY", "제목에 욕설이 있어요."},
                {"시1발 스피커", "급처", "PROFANITY", "제목에 욕설이 있어요."},
                {"병신같은 키보드", "그냥 가져가세요", "PROFANITY", "제목에 비속어가 있어요."},
                {"개새끼들 다 꺼져 의자 팝니다", "", "PROFANITY", "제목에 욕설과 모욕이 있어요."},
                {"존나 싼 마우스", "ㅈㄴ 깨끗함", "PROFANITY", "제목과 설명에 비속어가 있어요."},
                {"닌텐도 스위치", "사기꾼들 다 지랄하지 마라", "PROFANITY", "설명에 욕설이 있어요."},
                {"성인용품 미개봉", "19금 제품입니다", "SEXUAL", "성인용품은 올릴 수 없어요."},
                {"야한 사진집", "노출 많은 화보", "SEXUAL", "선정적인 내용이 있어요."},
                {"속옷 착용샷 판매", "착용 사진 보내드려요", "SEXUAL", "선정적인 내용이 있어요."},
                {"섹시 코스프레 사진", "노출 사진 포함", "SEXUAL", "선정적인 내용이 있어요."},
                {"특정 지역 사람 받지 않음", "그 동네 ○○들은 연락 사절", "HATE", "특정 집단을 비하하는 표현이 있어요."},
                {"외국인 사절 책상", "○○○ 출신은 더러워서 안 팝니다", "HATE", "출신을 비하하는 혐오 표현이 있어요."},
                {"나치 문양 깃발", "하켄크로이츠 기념품", "HATE", "혐오 상징이 들어간 물건이에요."},
                {"여자는 사지 마세요", "여자들은 물건을 망가뜨림", "HATE", "성별을 비하하는 표현이 있어요."},
                {"피 묻은 칼 판매", "실제 사용한 흔적 있음", "VIOLENCE", "잔인하거나 위협적인 내용이 있어요."},
                {"동물 사체 표본", "죽은 고양이 박제", "VIOLENCE", "잔인한 내용이 있어요."},
                {"전기충격기 개조품", "사람한테 써 봄", "VIOLENCE", "폭력적인 내용이 있어요."},
                {"협박용 가면", "이걸로 겁주면 다 돈 줌", "VIOLENCE", "위협 · 폭력을 부추기는 내용이 있어요."},
        };
        for (String[] b : bad) {
            rows.add(moderation(b[0], b[1], false, b[2], b[3]));
        }
        // 헷갈리기 쉬운 정상 사례(단어는 비슷하지만 괜찮음)
        String[][] fine = {
                {"주방용 식칼 세트", "새 상품, 한 번도 안 썼어요"}, {"수영복 새 상품", "택 그대로 있어요"},
                {"공포 영화 DVD 모음", "케이스 깨끗해요"}, {"의료용 마네킹", "간호학과 실습용"},
                {"닭가슴살 쿠폰", "기한 넉넉해요"}, {"군용 야전삽", "캠핑용으로 썼어요"},
        };
        for (String[] f : fine) {
            rows.add(moderation(f[0], f[1], true, "OK", ""));
        }

        Path out = root.resolve("training/data");
        Files.createDirectories(out);
        List<String> train = new ArrayList<>(), eval = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            (i % 10 == 7 ? eval : train).add(rows.get(i)); // 10개 중 1개는 평가용
        }
        Files.write(out.resolve("train.jsonl"), train, StandardCharsets.UTF_8);
        Files.write(out.resolve("eval.jsonl"), eval, StandardCharsets.UTF_8);
        System.out.printf("train=%d eval=%d (price=%d, moderation=%d)%n", train.size(), eval.size(),
                items.size() * 2, rows.size() - items.size() * 2);
    }

    static List<Item> parse(String sql) {
        // ('p01', 'dohyun', '스마트폰', '설명', 'DIGITAL', 'GOOD', 320000, 260000, 380000, 0.80, '근거', 30, 44, 0, 20960)
        Pattern row = Pattern.compile("\\('p\\d{2}', '[a-z]+', '((?:[^']|'')*)', '((?:[^']|'')*)',\\s*'([A-Z_]+)', '([A-Z_]+)', "
                + "(\\d+), (\\d+), (\\d+), ([0-9.]+),");
        List<Item> list = new ArrayList<>();
        Matcher m = row.matcher(sql);
        while (m.find()) {
            list.add(new Item(m.group(1).replace("''", "'"), m.group(2).replace("''", "'"), m.group(3), m.group(4),
                    Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), Integer.parseInt(m.group(7)),
                    Double.parseDouble(m.group(8))));
        }
        return list;
    }

    static String neighbor(String c) {
        return switch (c) {
            case "NEW" -> "LIKE_NEW";
            case "LIKE_NEW" -> "GOOD";
            case "GOOD" -> "FAIR";
            default -> "GOOD";
        };
    }

    /** 서버 PriceSanitizer 처럼 반올림(1만 미만 500원, 10만 미만 1,000원, 그 위 5,000원 단위) */
    static int round(double won) {
        int unit = won < 10_000 ? 500 : won < 100_000 ? 1_000 : 5_000;
        return (int) Math.max(unit, Math.round(won / unit) * unit);
    }

    static String label(String[][] table, String code) {
        for (String[] r : table) {
            if (r[0].equals(code)) {
                return r[1];
            }
        }
        return code;
    }

    /** AiPrompt.build(hint) 와 같은 문장 */
    static String pricePrompt(String title, String category, String condition) {
        StringBuilder cats = new StringBuilder(), conds = new StringBuilder();
        for (String[] c : CATEGORIES) {
            cats.append(cats.isEmpty() ? "" : ", ").append(c[0]).append('(').append(c[1]).append(')');
        }
        for (String[] c : CONDITIONS) {
            conds.append(conds.isEmpty() ? "" : ", ").append(c[0]).append('(').append(c[1]).append(')');
        }
        return "너는 한국 중고거래 시세에 밝은 물건 감정사다. 사진 속 물건을 보고 "
                + "한국 중고 시장에서 실제로 거래될 만한 가격을 원(KRW) 단위로 추정해라.\n"
                + "반드시 아래 형식의 JSON 객체 하나만 출력하고, 다른 문장이나 코드블록 표시는 쓰지 마라.\n"
                + "{\n"
                + "  \"title\": \"물건 이름(브랜드·모델을 알 수 있으면 포함, 25자 이내)\",\n"
                + "  \"category\": \"다음 중 하나의 코드: " + cats + "\",\n"
                + "  \"condition\": \"다음 중 하나의 코드: " + conds + "\",\n"
                + "  \"price_krw\": 정수,\n"
                + "  \"price_min_krw\": 정수,\n"
                + "  \"price_max_krw\": 정수,\n"
                + "  \"confidence\": 0과 1 사이 소수,\n"
                + "  \"reason\": \"가격 근거 1~2문장(브랜드, 사용감, 수요, 신품가 대비 비율)\",\n"
                + "  \"tags\": [\"검색 키워드\", 최대 5개]\n"
                + "}\n"
                + "규칙: 물건을 알아보기 어렵으면 confidence를 0.4 이하로 낮춰라. "
                + "사람·개인정보는 언급하지 마라.\n"
                + "판매자가 알려 준 정보(가능하면 따른다): 이름=" + title + " 카테고리=" + category + " 상태=" + condition + "\n";
    }

    static String price(Item it, String condition, boolean original) {
        double ratio = FACTOR.get(condition) / FACTOR.get(it.condition());
        int p = round(it.price() * ratio), lo = round(it.min() * ratio), hi = round(it.max() * ratio);
        String condLabel = label(CONDITIONS, condition);
        String reason = original
                ? "'" + it.title() + "'의 한국 중고 거래가와 " + condLabel + " 상태를 반영했어요. " + it.description()
                : "'" + it.title() + "'의 한국 중고 거래가에 " + condLabel + " 상태(감가 " + Math.round(FACTOR.get(condition) * 100)
                        + "%)를 반영했어요.";
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("title", it.title());
        answer.put("category", it.category());
        answer.put("condition", condition);
        answer.put("price_krw", p);
        answer.put("price_min_krw", Math.min(lo, p));
        answer.put("price_max_krw", Math.max(hi, p));
        answer.put("confidence", Math.round(it.confidence() * 100) / 100.0);
        answer.put("reason", reason);
        List<String> tags = new ArrayList<>();
        for (String w : it.title().split("\\s+")) {
            if (w.length() >= 2 && tags.size() < 3) {
                tags.add(w);
            }
        }
        tags.add(label(CATEGORIES, it.category()));
        tags.add("중고" + it.title().replace(" ", ""));
        answer.put("tags", tags.subList(0, Math.min(5, tags.size())));
        return row("price", pricePrompt(it.title(), it.category(), condition), json(answer));
    }

    /** ModerationService.prompt(title, description) 와 같은 문장 */
    static String moderationPrompt(String title, String description) {
        return "너는 한국 중고 물물교환 앱 '스바다'의 게시물 검수 담당이다.\n"
                + "아래 물건 사진과 글이 다른 사용자에게 보여도 되는지 판단해라.\n"
                + "막아야 하는 것:\n"
                + " - SEXUAL: 노출 · 성적인 사진이나 글\n"
                + " - HATE: 특정 집단 비하 · 혐오 표현 · 혐오 상징\n"
                + " - VIOLENCE: 피 · 잔인한 장면 · 무기로 위협하는 장면 · 시체 · 사체\n"
                + " - PROFANITY: 욕설 · 비속어 · 모욕\n"
                + "일반적인 물건 사진(전자기기 · 옷 · 책 · 가구 · 장난감 등)은 OK 다. 확실하지 않으면 OK 로 판단한다.\n"
                + "제목: " + title + "\n"
                + "설명: " + description + "\n"
                + "JSON 하나만 답해라(설명 문장 금지):\n"
                + "{\"allowed\": true 또는 false, \"category\": \"OK|SEXUAL|HATE|VIOLENCE|PROFANITY\", \"reason\": \"사용자에게 보여 줄 한 문장(한국어)\"}\n";
    }

    static String moderation(String title, String description, boolean allowed, String category, String reason) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("allowed", allowed);
        answer.put("category", category);
        answer.put("reason", reason);
        return row("moderation", moderationPrompt(title, description), json(answer));
    }

    static String row(String task, String user, String assistant) {
        return "{\"task\":" + str(task) + ",\"messages\":[{\"role\":\"user\",\"content\":" + str(user)
                + "},{\"role\":\"assistant\",\"content\":" + str(assistant) + "}]}";
    }

    static String json(Map<String, Object> m) {
        StringBuilder b = new StringBuilder("{");
        for (var e : m.entrySet()) {
            if (b.length() > 1) {
                b.append(", ");
            }
            b.append(str(e.getKey())).append(": ").append(value(e.getValue()));
        }
        return b.append('}').toString();
    }

    static String value(Object v) {
        if (v instanceof String s) {
            return str(s);
        }
        if (v instanceof List<?> l) {
            StringBuilder b = new StringBuilder("[");
            for (Object o : l) {
                b.append(b.length() > 1 ? ", " : "").append(value(o));
            }
            return b.append(']').toString();
        }
        return String.valueOf(v);
    }

    static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> b.append(c);
            }
        }
        return b.append('"').toString();
    }
}

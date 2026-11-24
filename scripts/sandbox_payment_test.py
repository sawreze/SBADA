"""금융 백엔드 샌드박스 시나리오 테스트 (2026-11-25, 파이썬 표준 라이브러리만 사용)

실제 돈은 움직이지 않는다: 결제 서비스(8098)는 PG · 은행을 부르지 않고 자기 DB 의 숫자만 바꾼다.
그래서 전자금융 규제 · PG 계약 없이 '돈이 오가는 흐름'을 처음부터 끝까지 실제 서버로 시험할 수 있다.

켜져 있는 메인 서버(8097) + 결제 서비스(8098)에 대해:
  A. 테스트 계정 2개(sbx…) + 물건 2개씩 만들기 (시연 계정 · 데이터는 건드리지 않음)
  B. 테스트 잔액 초기화(10만 원)
  C. 1원 계좌 인증(입금자명 숫자 4자리) → 계좌 등록
  D. 교환 요청 + 추가 금액 30,000원 제안 → 물건 주인이 50,000원으로 요구
  E. 요청자가 카카오페이로 안전결제(수수료 5% 요청자 부담) → 보관(PAID_HELD)
  F. 주인 수락 → 양쪽 수령 확인('물건 받았어요' 2/2) → 정산(RELEASED) → 주인 지갑 +50,000
  G. 주인 출금 → "출금되었습니다"
  H. 두 번째 교환: 지갑 잔액으로 결제 → 요청 취소 → 자동 환불(REFUNDED) → 잔액 원래대로
  I. 알림함에 결제 알림이 왔는지
  J. 정리: 남은 테스트 물건 내리기
결과는 PASS/FAIL 로 출력하고, 하나라도 실패하면 종료 코드 1. 토큰 · 비밀번호 · 계좌번호 전체는 출력하지 않는다.
"""
import json
import os
import random
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid
import zlib

BASE = os.environ.get("BASE", "http://127.0.0.1:8097")
PAY = os.environ.get("PAY", "http://127.0.0.1:8098")
RELAY_WAIT = 4  # 결제 서비스 이벤트가 메인 서버로 전달되는 시간(아웃박스 1.5초 주기)
results = []


def won(n):
    return f"{n:,}원"


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("  ✅ PASS " if ok else "  ❌ FAIL ") + name + (f"  — {detail}" if detail else ""))
    return ok


def api(method, path, token=None, body=None, base=None, raw=None, ctype="application/json; charset=utf-8"):
    """(상태 코드, JSON) 을 돌려준다. 4xx · 5xx 도 예외 없이 돌려준다."""
    data = raw if raw is not None else (None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8"))
    req = urllib.request.Request((base or BASE) + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", ctype)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            text = r.read().decode("utf-8")
            return r.status, (json.loads(text) if text else {})
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, {"raw": text[:200]}


def png(rgb):
    """64×64 단색 PNG(외부 라이브러리 없이)"""
    w = h = 64
    rows = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))

    def chunk(kind, data):
        c = kind + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def new_user(tag):
    name = "sbx" + uuid.uuid4().hex[:12]  # 아이디는 영문 소문자 · 숫자만(한글 tag 는 닉네임에만)
    pw = "sbx-" + uuid.uuid4().hex[:12]  # 테스트 계정 비밀번호(매번 새로, 출력하지 않음)
    s, _ = api("POST", "/api/auth/signup", body={"username": name, "password": pw, "nickname": "테스트" + tag})
    s2, d = api("POST", "/api/auth/login", body={"username": name, "password": pw})
    if s != 201 or s2 != 200:
        sys.exit(f"테스트 계정을 만들지 못했어요(가입 {s} {_.get('message', '') if isinstance(_, dict) else ''}, 로그인 {s2})")
    return {"name": name, "id": d["user"]["id"], "token": d["accessToken"], "nick": "테스트" + tag}


def new_item(user, title, rgb):
    boundary = "----sbx" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.png\"\r\n"
            f"Content-Type: image/png\r\n\r\n").encode() + png(rgb) + f"\r\n--{boundary}--\r\n".encode()
    s, img = api("POST", "/api/images", user["token"], raw=body, ctype="multipart/form-data; boundary=" + boundary)
    if s != 201:
        sys.exit(f"사진 올리기 실패({s}): {img}")
    s, an = api("POST", "/api/ai/analyze", user["token"],
                {"imageId": img["id"], "category": "DIGITAL", "condition": "GOOD"})
    if s != 200:
        sys.exit(f"AI 분석 실패({s}): {an}")
    s, item = api("POST", "/api/items", user["token"], {
        "title": title, "description": "결제 샌드박스 테스트용(자동으로 내려감)", "category": "DIGITAL",
        "condition": "GOOD", "imageIds": [img["id"]], "analysisId": an["analysisId"], "wishCategories": []})
    if s != 201:
        sys.exit(f"물건 등록 실패({s}): {item}")
    return item["id"]


def wallet(user):
    return api("GET", "/api/wallet", user["token"])[1]


def exchange(user, ex_id):
    return api("GET", f"/api/exchanges/{ex_id}", user["token"])[1]


def wait_payment(user, ex_id, want):
    for _ in range(RELAY_WAIT * 2):
        if exchange(user, ex_id).get("paymentStatus") == want:
            return True
        time.sleep(0.5)
    return exchange(user, ex_id).get("paymentStatus") == want


def main():
    print(f"=== 금융 백엔드 샌드박스 시나리오 ({time.strftime('%Y-%m-%d %H:%M:%S')}) ===")
    s, h = api("GET", "/api/payment/health", base=PAY)
    if s != 200:
        sys.exit("결제 서비스(8098)가 꺼져 있어요. 결제_샌드박스_테스트.command 로 켜 주세요.")
    check("결제 서비스 샌드박스 모드(실제 돈 X)", h.get("mode") == "SANDBOX" and h.get("realMoney") is False, str(h))

    print("\n[A] 테스트 계정 · 물건 준비")
    buyer, seller = new_user("구매"), new_user("판매")
    b_items = [new_item(buyer, "샌드박스 키보드", (40, 120, 200)), new_item(buyer, "샌드박스 마우스", (200, 80, 40))]
    s_items = [new_item(seller, "샌드박스 헤드폰", (60, 180, 90)), new_item(seller, "샌드박스 스피커", (150, 60, 160))]
    print(f"  계정 {buyer['name']}(id {buyer['id']}) · {seller['name']}(id {seller['id']}), 물건 4개")

    print("\n[B] 테스트 잔액 초기화")
    for u in (buyer, seller):
        s, r = api("POST", "/api/wallet/sandbox/reset", u["token"])
        check(f"{u['nick']} 잔액 100,000원", s == 200 and r.get("balance") == 100_000, r.get("message", r))

    print("\n[C] 1원 계좌 인증(판매자)")
    acct = "1000" + "".join(random.choice("0123456789") for _ in range(8))
    s, v = api("POST", "/api/wallet/accounts/verify", seller["token"],
               {"bankCode": "TOSSBANK", "accountNumber": acct, "holder": seller["nick"]})
    check("1원 보냄(입금자명 받음)", s == 200 and str(v.get("demoMemo", "")).startswith("쓰바다 "),
          f"{v.get('bankName')} {v.get('accountMasked')} / 입금자명 {v.get('demoMemo')}")
    code = str(v.get("demoMemo", "0000"))[-4:]
    wrong = f"{(int(code) + 1) % 10000:04d}"
    s, r = api("POST", f"/api/wallet/accounts/verify/{v.get('verificationId')}/confirm", seller["token"], {"code": wrong})
    check("틀린 숫자 → 400 + 남은 기회", s == 400 and r.get("code") == "WRONG_CODE", r.get("message"))
    s, acc = api("POST", f"/api/wallet/accounts/verify/{v.get('verificationId')}/confirm", seller["token"], {"code": code})
    check("맞는 숫자 → 계좌 등록(201)", s == 201 and acc.get("accountMasked") == "****" + acct[-4:],
          f"{acc.get('bankName')} {acc.get('accountMasked')}")

    print("\n[D] 교환 요청 + 추가 금액")
    s, ex = api("POST", "/api/exchanges", buyer["token"], {
        "targetItemId": s_items[0], "offeredItemId": b_items[0], "message": "샌드박스 테스트",
        "extraAmount": 30_000, "extraPayer": "ME"})
    ex_id = ex.get("id")
    check("요청 + '내가 30,000원 더 낼게요'", s == 201 and ex.get("extraAmount") == 30_000
          and ex.get("extraDirection") == "I_PAY", f"교환 #{ex_id}")
    s, r = api("POST", f"/api/exchanges/{ex_id}/extra", seller["token"], {"amount": 50_000, "payer": "OTHER"})
    check("주인이 '50,000원 더 주세요'로 요구", s == 200 and r.get("extraAmount") == 50_000
          and r.get("extraDirection") == "I_RECEIVE", r.get("paymentStatus"))
    s, r = api("POST", "/api/payments/escrow", buyer["token"], {"exchangeId": ex_id, "amount": 30_000, "method": "CARD"})
    check("약속과 다른 금액으로 결제 → 409", s == 409 and r.get("code") == "AMOUNT_MISMATCH", r.get("message"))
    s, r = api("POST", "/api/payments/escrow", seller["token"], {"exchangeId": ex_id, "amount": 50_000, "method": "CARD"})
    check("낼 사람이 아닌 쪽이 결제 → 403", s == 403 and r.get("code") == "NOT_PAYER", r.get("message"))

    print("\n[E] 안전결제(카카오페이)")
    s, q = api("GET", "/api/payments/quote?amount=50000", buyer["token"])
    check("수수료 5% 미리보기", q.get("fee") == 2_500 and q.get("total") == 52_500, f"총 {won(q.get('total', 0))}")
    s, r = api("POST", "/api/payments/escrow", buyer["token"], {"exchangeId": ex_id, "amount": 50_000, "method": "KAKAOPAY"})
    check("결제되었습니다(보관 중)", s == 200 and r.get("escrow", {}).get("status") == "HELD",
          f"{r.get('message')} · {won(-r.get('transaction', {}).get('amount', 0))} · 지갑 {won(r.get('balance', 0))}")
    s, r = api("POST", "/api/payments/escrow", buyer["token"], {"exchangeId": ex_id, "amount": 50_000, "method": "KAKAOPAY"})
    check("같은 교환 두 번 결제 → 409", s == 409 and r.get("code") == "ALREADY_PAID")
    check("교환 결제 상태 PAID_HELD(이벤트 반영)", wait_payment(seller, ex_id, "PAID_HELD"))
    check("판매자 지갑은 아직 그대로", wallet(seller).get("balance") == 100_000)

    print("\n[F] 수락 → 양쪽 수령 확인 → 정산")
    s, r = api("POST", f"/api/exchanges/{ex_id}/accept", seller["token"])
    check("판매자 수락(교환 성사)", s == 200 and r.get("status") == "ACCEPTED")
    s, r = api("POST", f"/api/exchanges/{ex_id}/complete", seller["token"])
    check("주인 '물건 받았어요'(1/2) → 아직 보관 중", s == 200 and r.get("paymentStatus") == "PAID_HELD"
          and r.get("iReceived") is True, r.get("message", ""))
    s, r = api("POST", f"/api/exchanges/{ex_id}/complete", buyer["token"])
    check("구매자 '물건 받았어요'(2/2) → RELEASED", s == 200 and r.get("paymentStatus") == "RELEASED", r.get("message", ""))
    check("판매자 지갑 150,000원", wallet(seller).get("balance") == 150_000, won(wallet(seller).get("balance", 0)))

    print("\n[G] 출금")
    s, r = api("POST", "/api/wallet/withdraw", seller["token"], {"accountId": acc.get("id"), "amount": 50_000})
    check("출금되었습니다", s == 200 and r.get("message") == "출금되었습니다", f"잔액 {won(r.get('balance', 0))}")

    print("\n[H] 지갑 결제 → 취소 → 자동 환불")
    s, ex2 = api("POST", "/api/exchanges", buyer["token"], {
        "targetItemId": s_items[1], "offeredItemId": b_items[1], "extraAmount": 20_000, "extraPayer": "ME"})
    ex2_id = ex2.get("id")
    before = wallet(buyer).get("balance")
    s, r = api("POST", "/api/payments/escrow", buyer["token"], {"exchangeId": ex2_id, "amount": 20_000, "method": "WALLET"})
    check("지갑 잔액으로 결제(21,000원 차감)", s == 200 and r.get("balance") == before - 21_000, won(r.get("balance", 0)))
    wait_payment(buyer, ex2_id, "PAID_HELD")
    s, r = api("POST", f"/api/exchanges/{ex2_id}/cancel", buyer["token"])
    check("요청 취소 → 환불 요청(REFUND_PENDING)", s == 200 and r.get("paymentStatus") in ("REFUND_PENDING", "REFUNDED"))
    check("환불 완료(REFUNDED)", wait_payment(buyer, ex2_id, "REFUNDED"))
    check("구매자 잔액 원래대로", wallet(buyer).get("balance") == before, won(wallet(buyer).get("balance", 0)))
    s, r = api("GET", f"/api/payments/escrow?exchangeId={ex2_id}", buyer["token"])
    check("안전결제 기록: 환불 완료", r.get("statusLabel") == "환불 완료")

    print("\n[I] 알림")
    time.sleep(RELAY_WAIT)
    for u in (buyer, seller):
        s, n = api("GET", "/api/notifications", u["token"])
        titles = [x["title"] for x in n.get("notifications", []) if x.get("type") == "WALLET"]
        check(f"{u['nick']} 결제 알림 받음", len(titles) >= 2, " · ".join(titles[:5]))

    print("\n[J] 정리: 남은 테스트 물건 내리기")
    for u, items in ((buyer, b_items), (seller, s_items)):
        for i in items:
            api("DELETE", f"/api/items/{i}", u["token"])
    print("  (테스트 계정은 남겨 둠: 이름이 sbx 로 시작)")

    passed = sum(1 for _, ok in results if ok)
    print(f"\n=== 결과: {passed}/{len(results)} 통과 ===")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())

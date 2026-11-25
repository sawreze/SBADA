# 전체 아키텍처 그림 만들기(2026-11-25): python3 docs/diagrams/make_architecture.py → 크롬 헤드리스로 PNG(5920×3820)
# google-chrome --headless=new --force-device-scale-factor=2 --window-size=2960,1910 --screenshot=docs/diagrams/architecture.png docs/diagrams/architecture.html
import html
OX,OY=40,300
W,H=2960,1910
TH={"app":("#2563EB","#EEF4FF"),"main":("#16A34A","#ECF8EF"),"item":("#7C3AED","#F4EFFF"),
    "pay":("#EA580C","#FFF3EA"),"kafka":("#CA8A04","#FFF9E6"),"ext":("#475569","#F3F5F8"),"flow":("#0F766E","#EAF7F5")}
out=[]
def esc(s): return html.escape(s)
def section(x,y,w,h,title,theme):
    c,bg=TH[theme]
    out.append(f'<div class="sec" style="left:{x+OX}px;top:{y+OY}px;width:{w}px;height:{h}px;background:{bg};border-color:{c}55">'
               f'<div class="sect" style="color:{c}">{esc(title)}</div></div>')
def box(x,y,w,h,text,theme,kind="box"):
    c,bg=TH[theme]
    lines=text.split("\n"); t=lines[0].replace("**","")
    body="<br>".join(esc(l) for l in lines[1:])
    cls="db" if kind=="db" else "box"
    bc=f'border-top-color:{c};border-left-color:#E2E8F0' if kind=='db' else f'border-left-color:{c}'
    out.append(f'<div class="{cls}" style="left:{x+OX}px;top:{y+OY}px;width:{w}px;height:{h}px;{bc}">'
               f'<div class="bt" style="color:{c}">{"🗄 " if kind=="db" else ""}{esc(t)}</div><div class="bb">{body}</div></div>')
def pill(x,y,w,h,text,theme):
    c,bg=TH[theme]
    out.append(f'<div class="pill" style="left:{x+OX}px;top:{y+OY}px;width:{w}px;height:{h}px;border-color:{c};color:#713F12">{esc(text)}</div>')
def label(x,y,text,color="#334155"):
    out.append(f'<div class="lab" style="left:{x+OX}px;top:{y+OY}px;color:{color};border-color:{color}55">{esc(text)}</div>')
def txt(x,y,text,cls,w=None):
    st=f"left:{x+OX}px;top:{y+OY}px;"+(f"width:{w}px;" if w else "")
    out.append(f'<div class="{cls}" style="{st}">{text}</div>')
arrows=[]
def arrow(pts,color="#334155",both=False,dash=False,lab=None,lp=None):
    arrows.append((pts,color,both,dash))
    if lab: label(lp[0],lp[1],lab,color)

# ── 제목 ──
txt(0,-262,"쓰바다(SBADA) 전체 아키텍처","h1")
txt(0,-196,"Flutter 앱 → 메인 서버(게이트웨이) → 물품 · 결제 서버 &nbsp;·&nbsp; <b>지금 답이 필요하면 gRPC</b>, <b>일어난 일은 Kafka</b> &nbsp;·&nbsp; 서비스마다 자기 DB","h2")
lg=[("app","앱"),("main","메인 서버"),("item","물품 서버"),("pay","결제 서버"),("kafka","허브(Kafka)"),("ext","외부 서비스")]
txt(0,-140," ".join(f'<span class="chip" style="background:{TH[k][1]};color:{TH[k][0]};border-color:{TH[k][0]}66">● {v}</span>' for k,v in lg)
    +' <span class="chip" style="background:#fff;color:#334155;border-color:#cbd5e1">→ 실선: 요청 &nbsp; ⇢ 점선: 이벤트(비동기)</span>',"leg")

# ── 구역 ──
section(0,0,400,1002,"📱 Flutter 앱 · iOS / Android / Web","app")
section(560,0,700,1000,"🟢 메인 서버  sbada-server · :8097 · gRPC :9097","main")
section(1420,0,440,710,"🟣 물품 서버  sbada-item · :8099 · gRPC :9099","item")
section(1920,0,440,710,"🟠 결제 서버  sbada-payment · :8098 · gRPC :9098","pay")
section(560,1140,1800,200,"🟡 허브 Kafka :9092","kafka")
section(2520,0,360,466,"🌐 외부 서비스 (물품 · 결제)","ext")
section(0,1140,400,200,"🌐 외부 서비스 (메인 · 앱)","ext")
section(0,1400,2880,160,"🔁 거래 한 건이 흘러가는 길 (2026-11-25 추가요금 · 결제 · 배송 · 후기)","flow")

# ── 앱 ──
AX,AW=24,352
box(AX,24,AW,110,"**ApiClient · Session**\nREST(JSON) + JWT Bearer · 자동 로그인\n오류 코드 → 한국어 메시지","app")
box(AX,158,AW,130,"**RealtimeClient**\nWebSocket /ws\nchat.message · exchange.updated\n끊기면 다시 연결 + 놓친 메시지 채우기","app")
box(AX,312,AW,130,"**Repositories**\nItem: 목록 GraphQL · 등록·상세 REST\nExchange · Chat · Toss · Wallet\nNotification · Review","app")
box(AX,466,AW,170,"**화면 (screens)**\n홈 · AI 추천 · 초성 검색 · 최신\n동네지도 · 물건 등록(사진 → AI 가격)\n채팅 · 거래 카드 · 알림함\n프로필(물건 + 후기) · 지갑 · 설정","app")
box(AX,660,AW,110,"**위젯**\n리퀴드 글래스 탭바 · 유리 스위치\nDealCard · 수락/결제/운송장/후기 창","app")
box(AX,794,AW,80,"**결제 WebView**\n토스 결제위젯 JS SDK v2 (test 키)","app")
box(AX,898,AW,80,"**지도 WebView · 푸시**\nLeaflet 타일 · 카카오맵 · FCM / APNs","app")

# ── 메인 ──
LX,RX,CW=584,920,316
box(LX,24,CW,110,"**보안 · 인증**\nJWT 필터 · 이메일 로그인\n카카오 로그인 · BCrypt 비밀번호","main")
box(LX,158,CW,130,"**실시간 RealtimeHub**\nWebSocket /ws (JWT 핸드셰이크)\n커밋 뒤 전송(AfterCommit)\n보는 사람마다 다른 교환 카드","main")
box(LX,312,CW,150,"**교환 ExchangeService**\n요청 → 바로 수락 /\n추가요금 요청(싼 물건 주인만)\n→ 수락 · 거절(다시 선택)\n결제 확인되면 자동 성사","main")
box(LX,486,CW,170,"**배송 ShippingService**\n운송장 7일 · 택배사 접수 확인\n1분마다 조회 → 채팅 카드 · 알림\n둘 다 도착 → 정산 · 거래 완료\n7일 지나면 취소 · 전액 환불","main")
box(LX,680,CW,90,"**후기 ReviewService**\n거래 완료 후 1번 · 별점 평균","main")
box(LX,794,CW,90,"**회원 · 선호 학습**\n본 것 · 찜 · 교환 → 추천 가중치","main")
box(LX,908,CW,68,"**시연 도구** /api/demo (dev 전용)","main")
box(RX,24,CW,130,"**게이트웨이 (프록시)**\n/api/items · /api/ai · /graphql → 물품\n/api/payments/** → 결제\n로그인 확인은 각 서버가 같은 규칙","main")
box(RX,178,CW,150,"**gRPC (배달부)**\n서버: UserService · ExchangeService\n클라이언트: ItemCatalog → 물품\nPaymentClient → 결제\n메타데이터 x-internal-token","main")
box(RX,352,CW,90,"**채팅 ChatService**\n방 · 메시지 · 읽음 · 거래 카드","main")
box(RX,466,CW,110,"**알림 NotificationService**\n알림함 · 종류별 알림 설정\nFCM / APNs 푸시","main")
box(RX,600,CW,170,"**H2 sbada** · Flyway V1~V21\napp_users · user_preferences\nexchange_requests · shipments\nreviews · chat_rooms · chat_messages\nnotifications · outbox · processed","main","db")
box(RX,794,CW,182,"**이벤트 (아웃박스)**\n같은 트랜잭션에 outbox_events\n→ OutboxRelay → Kafka 발행\nMainEventHandler 가 구독\nprocessed_events 로 한 번만 처리","main")

# ── 물품 ──
IX,IW=1444,392
box(IX,24,IW,90,"**REST**\n물건 등록·수정·내리기 · 사진 · 피드 · 찜","item")
box(IX,138,IW,90,"**GraphQL /graphql**\nitems · itemCount · myItems · userItems · myLikes","item")
box(IX,252,IW,110,"**AI 가격 분석**\nGemma (Ollama · Google AI) → 가격 보정\n키 없으면 카테고리 시세 × 상태","item")
box(IX,386,IW,70,"**콘텐츠 검사 · 추천 점수 · 초성 검색**","item")
box(IX,480,IW,90,"**gRPC ItemService**\nGet · CompleteTrade · ReopenTrade","item")
box(IX,594,IW,92,"**H2 item** · 사진 파일\nitems · images · ai_analyses · likes · outbox","item","db")

# ── 결제 ──
PX,PW=1944,392
box(PX,24,PW,110,"**지갑 · 계좌 (가상 돈)**\n1원 계좌 인증 · 출금\n계좌번호 RSA + AES 암호화","pay")
box(PX,158,PW,130,"**안전결제 Escrow**\n보관 HELD → 정산 RELEASED\n또는 환불 REFUNDED\n수수료 5% · 낸 사람 부담","pay")
box(PX,312,PW,130,"**토스 연동 TossService**\n주문 준비 · 승인(confirm) · 금액 확인\n가상계좌 · 웹훅 → 토스에 다시 조회\ntest 키만 (live 키 거절)","pay")
box(PX,466,PW,90,"**gRPC EscrowService**\n정산 · 환불 (메인 서버가 부름)","pay")
box(PX,580,PW,106,"**H2 payment**\nwallets · accounts · escrows\ntoss_orders · outbox","pay","db")

# ── Kafka ──
tops=["sbada.user.events","sbada.exchange.events","sbada.notifications","sbada.item.events","sbada.payment.events"]
for i,t in enumerate(tops): pill(584+i*344,1170,320,50,t,"kafka")
txt(584,1238,"물품 → 메인: 물건 상태 · 조회 · 찜 (요청 자동 취소 · 알림 · 선호 학습) &nbsp;|&nbsp; 결제 → 메인: 보관 · 정산 · 환불 (결제 상태 · 자동 성사)<br>"
    "메인 → 물품: 닉네임 변경 (판매자 이름) &nbsp;|&nbsp; 아웃박스(같은 트랜잭션) → 토픽 → 받는 쪽은 eventId 로 한 번만 처리 &nbsp;|&nbsp; Kafka 가 꺼져 있어도 이벤트는 outbox 에 쌓였다가 다시 켜지면 전송","note",1740)

# ── 외부 ──
box(2544,24,312,110,"**Gemma**\nOllama (맥 로컬) · Google AI Studio\n사진 → 이름 · 가격 · 근거","ext")
box(2544,312,312,130,"**토스페이먼츠 (테스트)**\n결제위젯 · 승인 · 가상계좌\n웹훅 DEPOSIT_CALLBACK\nPAYMENT_STATUS_CHANGED","ext")
for (x,y,t) in [(24,1164,"**카카오**\n로그인 · 지도"),(212,1164,"**스마트택배**\n운송장 조회"),(24,1252,"**FCM · APNs**\n푸시 알림"),(212,1252,"**Nominatim**\n좌표 → 동네 이름")]:
    box(x,y,164,68,t,"ext")
txt(2520,500,"<b>실행 · 배포</b><br>맥 .command: Kafka + 서버 3개 + 시뮬레이터<br>Docker Compose · Render (render.yaml)<br>비밀값: application-local.properties (깃 제외)<br>결제는 테스트 · 시연용 (실제 돈 이동 없음)","side",360)
txt(2520,650,"<b>기술</b><br>Flutter · Dart<br>Spring Boot 4 · Java 21 · JPA · Flyway<br>gRPC (protobuf, sbada-proto)<br>Kafka · GraphQL · WebSocket · H2","side",360)
txt(2520,800,"<b>테스트</b><br>메인 46 · 물품 37 · 결제 14 (JUnit)<br>앱 flutter analyze · flutter test<br>Kafka 왕복은 EmbeddedKafka","side",360)

# ── 흐름 ──
steps=[("① 교환 요청","앱 → 메인 · 물품 gRPC 로 물건 확인"),("② 수락 선택","바로 수락 / 추가요금 요청(싼 쪽)"),
       ("③ 추가요금 결제","토스 결제위젯 · 가상계좌 → 승인"),("④ 자동 성사","ESCROW_HELD → Kafka → 메인 · 물품 거래 완료"),
       ("⑤ 운송장 7일","스마트택배로 접수 확인 · 지나면 환불"),("⑥ 배송 현황","1분마다 조회 → 카드 · 알림 실시간"),
       ("⑦ 정산","둘 다 도착 → 결제 gRPC 정산 → 지갑"),("⑧ 후기","별점 · 글 → 프로필 평균 ★")]
sw=320; gap=(2880-48-sw*8)/7
for i,(a,b) in enumerate(steps):
    x=24+i*(sw+gap)
    box(int(x),1444,sw,92,f"**{a}**\n{b}","flow")
    if i<7: arrow([(x+sw,1490),(x+sw+gap,1490)],TH["flow"][0])

# ── 화살표 ──
K="#334155"
arrow([(376,79),(584,79)],TH["app"][0],lab="REST + JWT",lp=(420,52))
arrow([(376,223),(584,223)],TH["app"][0],lab="WebSocket",lp=(424,196))
arrow([(1236,89),(1340,89),(1340,69),(1444,69)],TH["item"][0],lab="HTTP 프록시",lp=(1262,30))
arrow([(1236,253),(1298,253),(1298,525),(1444,525)],TH["item"][0],lab="gRPC 거래 완료 · 다시 열기",lp=(1250,400))
arrow([(1236,300),(1370,300),(1370,770),(1890,770),(1890,511),(1944,511)],TH["pay"][0],lab="gRPC 정산 · 환불",lp=(1500,744))
arrow([(1836,307),(1880,307),(1880,-58),(2470,-58),(2470,79),(2544,79)],TH["ext"][0],lab="사진 분석",lp=(2120,-84))
arrow([(2336,377),(2544,377)],TH["ext"][0],both=True,lab="승인 · 웹훅",lp=(2384,350))
arrow([(1078,976),(1078,1170)],TH["kafka"][0],both=True,dash=True,lab="발행 · 구독",lp=(1092,1060))
arrow([(1640,710),(1640,1170)],TH["kafka"][0],dash=True,lab="item.events 발행",lp=(1654,1060))
arrow([(2140,710),(2140,1170)],TH["kafka"][0],dash=True,lab="payment.events 발행",lp=(2154,1060))
arrow([(584,571),(480,571),(480,1198),(376,1198)],TH["ext"][0],lab="운송장 조회",lp=(424,880))
arrow([(1236,1020),(1236,1036)],K)  # (빈 자리 채움 없음)
arrows.pop()

def svg():
    s=[f'<svg class="arr" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
       '<defs>']
    cols=set(c for _,c,_,_ in arrows)
    for c in cols:
        k=c.strip("#")
        s.append(f'<marker id="m{k}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{c}"/></marker>')
    s.append('</defs>')
    for pts,c,both,dash in arrows:
        k=c.strip("#")
        d=" ".join(f"{x+OX},{y+OY}" for x,y in pts)
        s.append(f'<polyline points="{d}" fill="none" stroke="{c}" stroke-width="3" stroke-linejoin="round" '
                 f'{"stroke-dasharray=\"10 7\" " if dash else ""}marker-end="url(#m{k})" {"marker-start=\"url(#m"+k+")\"" if both else ""}/>')
    s.append('</svg>')
    return "".join(s)

css="""
*{box-sizing:border-box;margin:0}
body{width:%dpx;height:%dpx;background:#FBFCFE;font-family:'Noto Sans CJK KR','Noto Sans KR',sans-serif;position:relative;overflow:hidden}
.sec{position:absolute;border:2px solid;border-radius:22px}
.sect{position:absolute;top:-34px;left:4px;font-size:21px;font-weight:800;white-space:nowrap}
.box,.db{position:absolute;background:#fff;border:1px solid #E2E8F0;border-left:6px solid;border-radius:12px;padding:9px 12px;
  box-shadow:0 2px 6px rgba(15,23,42,.06);overflow:hidden}
.db{background:#FFFFFF;border-radius:16px 16px 22px 22px;border-left-width:1px;border-top-width:6px;border-top-style:solid}
.bt{font-size:16.5px;font-weight:800;margin-bottom:3px;white-space:nowrap}
.bb{font-size:14px;line-height:1.5;color:#334155}
.pill{position:absolute;background:#FEF3C7;border:2px solid;border-radius:30px;display:flex;align-items:center;justify-content:center;
  font-size:17px;font-weight:700;font-family:'DejaVu Sans Mono',monospace}
.lab{position:absolute;background:#fff;border:1.5px solid;border-radius:8px;padding:1px 8px;font-size:13.5px;font-weight:700;white-space:nowrap;z-index:5}
.h1{position:absolute;font-size:52px;font-weight:900;color:#0F172A;white-space:nowrap}
.h2{position:absolute;font-size:23px;color:#334155;white-space:nowrap}
.leg{position:absolute;white-space:nowrap}
.chip{display:inline-block;border:1.5px solid;border-radius:20px;padding:4px 14px;font-size:16px;font-weight:700;margin-right:8px}
.note{position:absolute;font-size:14.5px;color:#713F12;line-height:1.6}
.side{position:absolute;font-size:14.5px;color:#334155;line-height:1.6;background:#fff;border:1px solid #E2E8F0;border-radius:12px;padding:10px 14px}
.arr{position:absolute;left:0;top:0;z-index:4}
.foot{position:absolute;font-size:13px;color:#94A3B8}
"""%(W,H)
txt(0,1580,"쓰바다 · sawreze/SBADA · 2026-11-25 기준","foot")
page=f"<!doctype html><html><head><meta charset='utf-8'><style>{css}</style></head><body>{''.join(out[:])}{svg()}</body></html>"
open("docs/diagrams/architecture.html","w").write(page)
print("ok")

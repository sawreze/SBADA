package com.sbada.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * DB 변경이 확정(COMMIT)된 뒤에 실시간 전송을 한다.
 * 커밋 전에 WebSocket으로 알리면, 앱이 곧바로 다시 조회했을 때 아직 없는 자료를 볼 수 있기 때문이다.
 *
 * [프로그래밍 설명]
 *  - 트랜잭션: @Transactional 메서드 안의 DB 작업은 메서드가 정상 종료될 때 한꺼번에 COMMIT(확정)되고,
 *    예외가 나면 ROLLBACK(취소)된다. 즉 메서드 '도중'에는 아직 다른 요청이 그 변경을 볼 수 없다.
 *  - TransactionSynchronizationManager: 지금 스레드에 진행 중인 트랜잭션이 있는지 알려 주고,
 *    '커밋 직후에 실행할 일'(TransactionSynchronization#afterCommit)을 등록할 수 있게 해 준다.
 *  - 사용 예: AfterCommit.run(() -> hub.sendTo(userId, 메시지));  ← 람다(Runnable)로 '나중에 할 일'을 넘긴다.
 *  - final 클래스 + private 생성자 = 객체를 만들지 않고 static 메서드만 쓰는 '유틸리티 클래스' 패턴.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 안에서 불렸다 → 지금 실행하지 않고 '커밋 성공 후'에 실행되도록 예약한다.
            // (롤백되면 afterCommit 은 불리지 않으므로, 실패한 변경을 알리는 일도 없다)
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            // 트랜잭션 밖이면 기다릴 커밋이 없으므로 바로 실행한다.
            task.run();
        }
    }
}

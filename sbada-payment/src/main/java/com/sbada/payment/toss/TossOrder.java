package com.sbada.payment.toss;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 토스페이먼츠 주문 하나(V3 toss_orders). 상태: READY · WAITING_FOR_DEPOSIT · DONE · CANCELED · FAILED */
@Entity
@Table(name = "toss_orders")
public class TossOrder {

    public static final String READY = "READY";
    public static final String WAITING = "WAITING_FOR_DEPOSIT";
    public static final String DONE = "DONE";
    public static final String CANCELED = "CANCELED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;
    @Column(name = "exchange_id", nullable = false)
    private Long exchangeId;
    @Column(name = "payer_id", nullable = false)
    private Long payerId;
    @Column(name = "payee_id", nullable = false)
    private Long payeeId;
    @Column(nullable = false)
    private Long amount;
    @Column(nullable = false)
    private Long fee;
    @Column(nullable = false)
    private Long total;
    /** CARD(토스 결제창: 카드 · 간편결제 등) · VIRTUAL_ACCOUNT(가상계좌) */
    @Column(nullable = false, length = 20)
    private String method;
    @Column(nullable = false, length = 24)
    private String status;
    @Column(name = "payment_key", length = 200)
    private String paymentKey;
    @Column(name = "va_bank", length = 30)
    private String vaBank;
    @Column(name = "va_account_number", length = 40)
    private String vaAccountNumber;
    @Column(name = "va_holder", length = 40)
    private String vaHolder;
    @Column(name = "va_due_date", length = 40)
    private String vaDueDate;
    @Column(length = 200)
    private String message;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    protected TossOrder() {
    }

    public TossOrder(String orderId, long exchangeId, long payerId, long payeeId, long amount, long fee, String method) {
        this.orderId = orderId;
        this.exchangeId = exchangeId;
        this.payerId = payerId;
        this.payeeId = payeeId;
        this.amount = amount;
        this.fee = fee;
        this.total = amount + fee;
        this.method = method;
        this.status = READY;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.checkedAt = createdAt;
    }

    void paymentKey(String key) {
        if (key != null && !key.isBlank()) {
            this.paymentKey = key;
        }
        touch();
    }

    /** 결제위젯에서는 사용자가 위젯 안에서 수단을 고르므로, 토스가 알려 준 실제 수단으로 바꿔 둔다 */
    void actualMethod(String tossMethod) {
        if ("가상계좌".equals(tossMethod)) {
            this.method = "VIRTUAL_ACCOUNT";
        } else if (tossMethod != null && !tossMethod.isBlank()) {
            this.method = "CARD";
        }
    }

    void waiting(String bank, String account, String holder, String due) {
        this.status = WAITING;
        this.vaBank = bank;
        this.vaAccountNumber = account;
        this.vaHolder = holder;
        this.vaDueDate = due;
        touch();
    }

    void done() {
        this.status = DONE;
        touch();
    }

    void close(String status, String message) {
        this.status = status;
        this.message = message == null ? null : (message.length() > 200 ? message.substring(0, 200) : message);
        touch();
    }

    void checked() {
        this.checkedAt = Instant.now();
    }

    private void touch() {
        this.updatedAt = Instant.now();
        this.checkedAt = updatedAt;
    }

    public boolean isVirtualAccount() {
        return "VIRTUAL_ACCOUNT".equals(method);
    }

    public Long getId() { return id; }
    public String getOrderId() { return orderId; }
    public Long getExchangeId() { return exchangeId; }
    public Long getPayerId() { return payerId; }
    public Long getPayeeId() { return payeeId; }
    public Long getAmount() { return amount; }
    public Long getFee() { return fee; }
    public Long getTotal() { return total; }
    public String getMethod() { return method; }
    public String getStatus() { return status; }
    public String getPaymentKey() { return paymentKey; }
    public String getVaBank() { return vaBank; }
    public String getVaAccountNumber() { return vaAccountNumber; }
    public String getVaHolder() { return vaHolder; }
    public String getVaDueDate() { return vaDueDate; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCheckedAt() { return checkedAt; }
}

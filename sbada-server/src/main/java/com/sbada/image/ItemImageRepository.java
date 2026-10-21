package com.sbada.image;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * item_images 테이블 접근. 추가 메서드 없이 JpaRepository 의 기본 메서드(save, findById, findAllById …)만 쓴다.
 * 인터페이스가 비어 있어도 스프링이 구현체를 만들어 빈으로 등록한다.
 */
public interface ItemImageRepository extends JpaRepository<ItemImage, Long> {
}

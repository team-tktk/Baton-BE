package com.baton.handover;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인수인계가 다루는 업무 범위 한 줄. Handover 아그리게잇에 종속되며 단독으로 존재하지 않는다.
 */
@Entity
@Table(name = "work_scopes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkScope {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "handover_id", nullable = false)
	private Handover handover;

	@Column(nullable = false)
	private String title;

	@Column(columnDefinition = "text")
	private String description;

	WorkScope(Handover handover, String title, String description) {
		this.handover = handover;
		this.title = title;
		this.description = description;
	}
}

package com.baton.masking;

/** 마스킹 후보가 어디서 왔는지. 직접 추가한 후보만 삭제할 수 있다. */
public enum MaskingOrigin {

	DETECTED,  // 서버가 자동으로 찾음
	MANUAL     // 사용자가 직접 구간을 지정함
}

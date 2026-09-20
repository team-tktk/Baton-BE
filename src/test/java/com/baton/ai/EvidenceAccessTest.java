package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import com.baton.auth.AuthService;
import com.baton.auth.User;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverAccess;
import com.baton.handover.HandoverPermission;
import com.baton.handover.HandoverRepository;

class EvidenceAccessTest {
	@Test
	void nonParticipantCannotResolveEvidenceOrDownloadOriginal() {
		var ingest = mock(RagIngestService.class);
		var repository = mock(HandoverRepository.class);
		var auth = mock(AuthService.class);
		var versionService = mock(RagSourceVersionService.class);
		var authentication = mock(Authentication.class);
		var user = mock(User.class);
		UUID handoverId = UUID.randomUUID();
		UUID sourceId = UUID.randomUUID();
		when(repository.findById(handoverId)).thenReturn(Optional.of(Handover.create(UUID.randomUUID(), "test")));
		when(authentication.getName()).thenReturn("viewer@example.com");
		when(auth.getByEmail("viewer@example.com")).thenReturn(user);
		when(user.getId()).thenReturn(UUID.randomUUID());
		var permission = new HandoverPermission();
		var controller = new RagController(ingest, null, null, null, repository,
				permission, auth, null, null, null, new HandoverAccess(repository, permission, auth), versionService);
		assertThatThrownBy(() -> controller.getEvidence(handoverId, sourceId, "quote", authentication))
				.isInstanceOfSatisfying(BusinessException.class, e ->
						org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo(ErrorCode.HANDOVER_FORBIDDEN));
		assertThatThrownBy(() -> controller.downloadFile(handoverId, sourceId, authentication))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> controller.getSourceVersions(handoverId, sourceId, 0, 20, authentication))
				.isInstanceOf(BusinessException.class);
		verifyNoInteractions(ingest);
		verifyNoInteractions(versionService);
	}

	@Test
	void fileFromDifferentHandoverCannotBeReadEvenWithValidFileId() {
		var repository = mock(SourceDocumentRepository.class);
		var storage = mock(S3FileStorage.class);
		UUID sourceId = UUID.randomUUID();
		when(repository.findById(sourceId)).thenReturn(Optional.of(
				SourceDocument.create(UUID.randomUUID(), "private.pdf", "application/pdf", 10, "private-key")));
		var ingest = new RagIngestService(repository, null, null, null, storage, null, null, null, null, null, null, null);
		UUID otherHandover = UUID.randomUUID();
		assertThatThrownBy(() -> ingest.getSource(otherHandover, sourceId)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> ingest.download(otherHandover, sourceId)).isInstanceOf(BusinessException.class);
		verifyNoInteractions(storage);
	}
}

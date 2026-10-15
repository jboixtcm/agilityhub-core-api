package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformClubContractGuardsTest {
    @Test void T_17_12_R_17_12_uploadUsesTargetClubImageTypesAndExactSizeLimit() {
        var configs=mock(ClubConfigService.class);
        var config=mock(ClubConfig.class);
        when(configs.current("target-club")).thenReturn(config);
        when(config.get("files.allowedTypes",List.class)).thenReturn(List.of("image/png","application/pdf"));
        when(config.get("files.maxSizeMb",Integer.class)).thenReturn(1);
        var guards=new PlatformClubContractGuards(mock(ClubRepository.class),mock(ParameterCatalog.class),configs);
        assertThatCode(()->guards.themeUpload("target-club","image/png",1024*1024)).doesNotThrowAnyException();
        assertThatThrownBy(()->guards.themeUpload("target-club","image/png",1024*1024+1))
                .isInstanceOfSatisfying(ApiException.class,error->{
                    assertThat(error.code()).isEqualTo(ErrorCode.FILE_TOO_LARGE);
                    assertThat(error.details()).containsEntry("maxSizeMb",1);
                });
        for(String type:List.of("image/jpeg","application/pdf"))
            assertThatThrownBy(()->guards.themeUpload("target-club",type,3))
                    .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED));
        when(config.get("files.allowedTypes",List.class)).thenReturn(List.of("image/*"));
        assertThatCode(()->guards.themeUpload("target-club","image/jpeg",3)).doesNotThrowAnyException();
        verify(configs,times(5)).current("target-club");
        verifyNoMoreInteractions(configs);
    }
}

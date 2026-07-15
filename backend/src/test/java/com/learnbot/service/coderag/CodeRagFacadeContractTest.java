package com.learnbot.service.coderag;

import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeEvidence;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.service.CodeRagService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeRagFacadeContractTest {

    @Test
    void facadeRetainsTheExistingRequestEntryPoints() throws NoSuchMethodException {
        assertPublicResponseMethod("ask", UUID.class, String.class, String.class, Integer.class);
        assertPublicResponseMethod(
                "ask", UUID.class, UUID.class, List.class, String.class, String.class, Integer.class);
        assertPublicResponseMethod(
                "askConversational", UUID.class, UUID.class, List.class, String.class, String.class,
                Integer.class, RagConversationContext.class);
        assertPublicResponseMethod(
                "askStreaming", UUID.class, UUID.class, List.class, String.class, String.class,
                Integer.class, CodeRagService.CodeAnswerStreamSink.class);
        assertPublicResponseMethod(
                "askConversationalStreaming", UUID.class, UUID.class, List.class, String.class, String.class,
                Integer.class, RagConversationContext.class, CodeRagService.CodeAnswerStreamSink.class);
    }

    @Test
    void facadeRetainsTheStreamingCallbackContract() throws NoSuchMethodException {
        Class<?> sink = CodeRagService.CodeAnswerStreamSink.class;

        assertThat(sink.isInterface()).isTrue();
        assertVoidMethod(sink, "onStatus", String.class, String.class);
        assertVoidMethod(sink, "onEvidence", List.class);
        assertVoidMethod(sink, "onDelta", String.class);
        assertVoidMethod(sink, "onReplace", String.class, String.class);

        Method onStatus = sink.getMethod("onStatus", String.class, String.class);
        assertThat(onStatus.isDefault()).isTrue();
        assertThat(sink.getMethod("onEvidence", List.class).getGenericParameterTypes()[0].getTypeName())
                .contains(CodeEvidence.class.getName());
    }

    private static void assertPublicResponseMethod(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = CodeRagService.class.getMethod(name, parameterTypes);
        assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        assertThat(method.getReturnType()).isEqualTo(CodeAskResponse.class);
    }

    private static void assertVoidMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameterTypes);
        assertThat(method.getReturnType()).isEqualTo(void.class);
    }
}

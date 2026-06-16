package com.learnbot.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class CodeContentReader {
    public String read(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (looksBinary(bytes)) {
                throw new IllegalArgumentException("바이너리 파일은 색인하지 않습니다: " + path.getFileName());
            }
            try {
                return decode(bytes, StandardCharsets.UTF_8);
            } catch (CharacterCodingException ex) {
                try {
                    return decode(bytes, Charset.forName("MS949"));
                } catch (CharacterCodingException fallbackEx) {
                    throw new IllegalArgumentException("지원하지 않는 문자 인코딩입니다: " + path.getFileName(), fallbackEx);
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("코드 파일을 읽을 수 없습니다: " + path.getFileName(), ex);
        }
    }

    private String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private boolean looksBinary(byte[] bytes) {
        int sample = Math.min(bytes.length, 2048);
        for (int i = 0; i < sample; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}

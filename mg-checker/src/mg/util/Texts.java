package mg.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 파일 읽기와 경로 안전 검사. */
public final class Texts {

    private Texts() {
    }

    /**
     * UTF-8로 읽고, 깨지면 한국어 Windows 기본 인코딩(MS949)으로 다시 읽는다.
     * Eclipse에서 만든 오래된 프로젝트는 MS949로 저장된 경우가 있다.
     */
    public static String read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            Charset fallback = Charset.isSupported("MS949") ? Charset.forName("MS949") : StandardCharsets.ISO_8859_1;
            s = new String(bytes, fallback);
        }
        if (!s.isEmpty() && s.charAt(0) == '﻿') {
            s = s.substring(1);
        }
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * root 아래의 상대 경로를 실제 파일로 바꾼다. ".."나 심볼릭 링크로 root 밖을 가리키면 거부한다.
     */
    public static Path resolveInside(Path root, String rel) throws IOException {
        if (rel == null || rel.isBlank()) {
            throw new IOException("파일 경로가 비어 있습니다.");
        }
        Path realRoot = root.toRealPath();
        Path p = realRoot.resolve(rel.replace('\\', '/')).normalize();
        if (!p.startsWith(realRoot)) {
            throw new IOException("프로젝트 폴더 밖의 파일은 읽을 수 없습니다: " + rel);
        }
        if (!Files.isRegularFile(p)) {
            throw new IOException("파일이 없습니다: " + rel);
        }
        Path real = p.toRealPath();
        if (!real.startsWith(realRoot)) {
            throw new IOException("프로젝트 폴더 밖을 가리키는 링크입니다: " + rel);
        }
        return real;
    }

    public static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}

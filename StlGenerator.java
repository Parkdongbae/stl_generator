// =============================================================================
//  STL Generator — OpenSCAD 부재별 STL 내보내기 로컬 웹앱 (단일 파일)
//
//  * Java(JDK 17+) + 내장 HTML/CSS/JS 한 파일. 외부 라이브러리 없음(JDK 내장 HttpServer).
//  * 실행:  javac -encoding UTF-8 StlGenerator.java   →   java StlGenerator
//  * 브라우저에서 http://localhost:8765 자동 오픈 (포트 점유 시 8766~ 자동 탐색)
//
//  주요 기능
//   1. SCAD 파일 불러오기 → 코드 미리보기
//   2. 부재 자동 감지 (두 가지 모드)
//      - PART 모드 : PART/IDX 플래그 방식 파일 (예: 정자_부재별_STL.scad)
//                    부재 종류를 체크 → openscad -D 'PART="03_gidung"' 로 렌더
//                    행별 IDX 입력: 비우면 그 종류 전체, 숫자면 해당 번호 1개
//      - MODULE 모드 : 일반 파일의 최상위 module 을 체크 → wrapper(use)로 렌더
//   3. 내보내기: 선택 부재 → 1개 STL 병합  또는  부재별 개별 STL
//      - 병합: PART 모드는 부재별 렌더 후 바이너리 STL 병합 / MODULE 모드는 1회 렌더
//   4. 저장 폴더: 서버 측 폴더 브라우저(드라이브/탐색/새 폴더 생성) + 직접 입력
//   5. 결과 테이블(파일/상태/크기/로그) + 결과 폴더 탐색기로 열기
// =============================================================================

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class StlGenerator {

    static final int PORT_START = 8765;
    static final long OPENSCAD_TIMEOUT_SEC = 1800; // 부재 1회 렌더 최대 대기(기와 등 대량 부재 대응)
    static final String JSON_TYPE = "application/json; charset=utf-8";

    static final Pattern MODULE_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    static final Pattern PART_NAME = Pattern.compile("^\\d\\d_[A-Za-z0-9_]+$");
    static final Pattern PART_LIT = Pattern.compile("\\d\\d_[A-Za-z0-9_]+");
    static final Pattern PART_VAR = Pattern.compile("(?m)^\\s*PART\\s*=");
    static final Pattern PART_COUNT = Pattern.compile("(?m)^\\s*//\\s*(\\d\\d_[A-Za-z0-9_]+)[^\\n]*\\((\\d+)개\\)");

    static String openScadPath = null;
    static boolean binFormatSupported = false; // --export-format binstl 지원 여부

    static final int MAX_RUNS_PER_JOB = 500; // 한 작업당 최대 렌더 횟수(IDX 열거 폭주 방지)
    static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
    static final AtomicInteger JOB_SEQ = new AtomicInteger();

    static final Object SETTINGS_LOCK = new Object();
    static final List<String> recentScads = new ArrayList<>();
    static final List<String> recentFolders = new ArrayList<>();

    // =========================================================================
    //  main
    // =========================================================================
    public static void main(String[] args) throws IOException {
        openScadPath = findOpenScad();
        if (openScadPath != null) detectExportFormatSupport();

        HttpServer server = null;
        int port = PORT_START;
        for (int i = 0; i < 10; i++) {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
                break;
            } catch (IOException bindFail) {
                port++;
            }
        }
        if (server == null) {
            System.err.println("사용 가능한 포트(" + PORT_START + "~" + (PORT_START + 9) + ")를 찾지 못했습니다.");
            return;
        }

        server.createContext("/", StlGenerator::handleRoot);
        server.createContext("/api/env", StlGenerator::handleEnv);
        server.createContext("/api/load", StlGenerator::handleLoad);
        server.createContext("/api/upload", StlGenerator::handleUpload);
        server.createContext("/api/browse", StlGenerator::handleBrowse);
        server.createContext("/api/mkdir", StlGenerator::handleMkdir);
        server.createContext("/api/export", StlGenerator::handleExport);
        server.createContext("/api/job", StlGenerator::handleJobStatus);
        server.createContext("/api/cancel", StlGenerator::handleCancel);
        server.createContext("/api/preview", StlGenerator::handlePreview);
        server.createContext("/api/settings", StlGenerator::handleSettings);
        server.createContext("/api/openfolder", StlGenerator::handleOpenFolder);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        loadSettings();

        String url = "http://localhost:" + port;
        System.out.println("==============================================================");
        System.out.println("  STL Generator  v1.0");
        System.out.println("  OpenSCAD 부재별 STL 내보내기 로컬 웹앱 (단일 Java 파일)");
        System.out.println("--------------------------------------------------------------");
        System.out.println("  접속 주소 : " + url + "   (브라우저 자동 실행)");
        System.out.println("  OpenSCAD  : " + (openScadPath == null ? "미발견 - 설치 또는 PATH 등록 필요" : openScadPath));
        System.out.println("  바이너리 STL 출력 : " + (binFormatSupported ? "지원 (병합 최적)" : "미지원 - ASCII 출력 후 텍스트 병합"));
        System.out.println("  서버 종료 : 콘솔에서 Enter 입력 또는 Ctrl+C");
        System.out.println("==============================================================");
        openBrowser(url);

        try {
            new Scanner(System.in).nextLine();
        } catch (NoSuchElementException eof) {
            System.out.println("표준 입력 종료 — 서버를 계속 실행합니다. 종료는 Ctrl+C");
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        server.stop(0);
        System.out.println("서버 종료");
    }

    // =========================================================================
    //  OpenSCAD 탐색 / 브라우저 오픈
    // =========================================================================
    static String findOpenScad() {
        String env = System.getenv("OPENSACD_PATH");
        if (env != null && !env.isBlank() && new File(env.trim()).isFile()) return env.trim();
        String[] candidates = {
                "C:\\Program Files\\OpenSCAD\\openscad.exe",
                "C:\\Program Files (x86)\\OpenSCAD\\openscad.exe",
                System.getenv("LOCALAPPDATA") + "\\Programs\\OpenSCAD\\openscad.exe"
        };
        for (String c : candidates) if (c != null && new File(c).isFile()) return c;
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(";")) {
                File f = new File(dir.trim(), "openscad.exe");
                if (f.isFile()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    /** --export-format 옵션 지원 여부 감지 (2021.01 등 최신 버전: binstl 가능) */
    static void detectExportFormatSupport() {
        try {
            CmdResult r = runCommand(List.of(openScadPath, "--help"), 20);
            binFormatSupported = r.output() != null && r.output().contains("export-format");
        } catch (IOException e) {
            binFormatSupported = false;
            System.err.println("OpenSCAD --help 확인 실패: " + e.getMessage());
        }
    }

    /** STL 내보내기 명령 공통 접두부 (가능하면 바이너리 STL로 출력) */
    static List<String> exportCmd(Path out) {
        List<String> cmd = new ArrayList<>();
        cmd.add(openScadPath);
        if (binFormatSupported) {
            cmd.add("--export-format");
            cmd.add("binstl");
        }
        cmd.add("-o");
        cmd.add(out.toString());
        return cmd;
    }

    static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop d = Desktop.getDesktop();
                if (d.isSupported(Desktop.Action.BROWSE)) {
                    d.browse(new URI(url));
                    return;
                }
            }
        } catch (Exception e) {
            System.out.println("브라우저 자동 실행 실패(" + e.getMessage() + ") — 아래 주소를 직접 여세요.");
        }
        System.out.println("브라우저에서 접속: " + url);
    }

    // =========================================================================
    //  SCAD 분석: 부재 감지
    // =========================================================================

    /** PART/IDX 플래그 방식 감지: PART 변수 존재 + NN_이름 리터럴 2개 이상 */
    static List<String> parseParts(String src) {
        List<String> names = new ArrayList<>();
        if (!PART_VAR.matcher(src).find()) return names;
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Matcher mt = PART_LIT.matcher(src);
        while (mt.find()) set.add(mt.group());
        names.addAll(set);
        Collections.sort(names);
        return names;
    }

    /** 주석의 "01_gidan 기단 (3개)" 형식에서 부재별 개수 파싱(정보 표시용) */
    static Map<String, Integer> parsePartCounts(String src) {
        Map<String, Integer> map = new HashMap<>();
        Matcher mt = PART_COUNT.matcher(src);
        while (mt.find()) map.put(mt.group(1), Integer.parseInt(mt.group(2)));
        return map;
    }

    /** 최상위 module 선언 수집 (주석/문자열 무시, 중괄호 깊이 0 기준) */
    static List<String> parseModules(String src) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        int n = src.length(), depth = 0, i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                i = Math.min(n, i + 2);
            } else if (c == '"') {
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
            } else if (c == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                depth--;
                i++;
            } else if (depth == 0 && Character.isLetter(c)) {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                String word = src.substring(start, i);
                if (word.equals("module")) {
                    while (i < n && Character.isWhitespace(src.charAt(i))) i++;
                    int s2 = i;
                    while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                    if (i > s2) set.add(src.substring(s2, i));
                }
            } else {
                i++;
            }
        }
        return new ArrayList<>(set);
    }

    /** MODULE 모드용 wrapper: use 로 원본 모듈만 가져와 호출 (원본 최상위 도형 미포함) */
    static String buildWrapper(Path scad, List<String> mods) {
        StringBuilder sb = new StringBuilder();
        sb.append("// STL Generator가 생성한 내보내기 wrapper\n");
        sb.append("use <").append(scad.toAbsolutePath().normalize().toString().replace('\\', '/')).append(">\n\n");
        for (String mn : mods) sb.append(mn).append("();\n");
        return sb.toString();
    }

    // =========================================================================
    //  프로세스 실행 / STL 병합
    // =========================================================================
    record CmdResult(int code, String output, boolean timeout) {}

    static CmdResult runCommand(List<String> cmd, long timeoutSec) throws IOException {
        return runCommand(cmd, timeoutSec, null);
    }

    static CmdResult runCommand(List<String> cmd, long timeoutSec, Consumer<Process> onStart) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (onStart != null) onStart.accept(p);
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (out) {
                        out.append(line).append('\n');
                    }
                }
            } catch (IOException io) {
                synchronized (out) {
                    out.append("[출력 수집 중단: ").append(String.valueOf(io.getMessage())).append("]\n");
                }
            }
        });
        reader.setDaemon(true);
        reader.start();
        boolean done;
        try {
            done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new CmdResult(-3, "대기 중단", true);
        }
        if (!done) {
            p.destroyForcibly();
            try {
                reader.join(1000);
            } catch (InterruptedException ie2) {
                Thread.currentThread().interrupt();
            }
            return new CmdResult(-2, out + "\n[타임아웃 — 프로세스 강제 종료]", true);
        }
        try {
            reader.join(3000);
        } catch (InterruptedException ie3) {
            Thread.currentThread().interrupt();
        }
        return new CmdResult(p.exitValue(), out.toString(), false);
    }

    /** 바이너리 STL 병합: 헤더 80B + 삼각형수 uint32 + 삼각형 50B/개 */
    static long triangleCount(Path p) throws IOException {
        byte[] b = new byte[84];
        try (InputStream in = Files.newInputStream(p)) {
            int off = 0;
            while (off < 84) {
                int r = in.read(b, off, 84 - off);
                if (r < 0) return -1;
                off += r;
            }
        }
        return (b[80] & 0xFFL) | ((b[81] & 0xFFL) << 8) | ((b[82] & 0xFFL) << 16) | ((b[83] & 0xFFL) << 24);
    }

    /** 바이너리/ASCII 형식을 판별해 알맞은 병합기로 위임 */
    static boolean isBinaryStl(Path p) throws IOException {
        long len = Files.size(p);
        if (len < 84) return false;
        long n = triangleCount(p);
        return n >= 0 && len == 84 + n * 50;
    }

    static void mergeStls(List<Path> inputs, Path out) throws IOException {
        boolean bin = isBinaryStl(inputs.get(0));
        for (Path p : inputs) {
            if (isBinaryStl(p) != bin)
                throw new IOException("형식이 섞인 STL은 병합할 수 없습니다: " + p.getFileName());
        }
        if (bin) mergeBinaryStls(inputs, out);
        else mergeAsciiStls(inputs, out);
    }

    /** ASCII STL 텍스트 병합: solid/endsolid 헤더를 제거하고 본문을 이어 붙임 */
    static void mergeAsciiStls(List<Path> inputs, Path out) throws IOException {
        StringBuilder sb = new StringBuilder("solid merged_by_stl_generator\n");
        for (Path p : inputs) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
            int firstNl = text.indexOf('\n');
            int lastEnd = text.lastIndexOf("endsolid");
            if (!text.startsWith("solid") || firstNl < 0 || lastEnd < 0)
                throw new IOException("ASCII STL 형식이 아닙니다: " + p.getFileName());
            sb.append(text, firstNl + 1, lastEnd).append('\n');
        }
        sb.append("endsolid merged_by_stl_generator\n");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 균일 스케일 적용 (형식 자동 판별). 균일 배율에서는 법선 방향이 보존되므로 정점 좌표만 스케일 */
    static void scaleStl(Path p, double s) throws IOException {
        if (isBinaryStl(p)) scaleBinaryStl(p, s);
        else scaleAsciiStl(p, s);
    }

    static void scaleBinaryStl(Path p, double s) throws IOException {
        byte[] data = Files.readAllBytes(p);
        if (data.length < 84) throw new IOException("손상된 STL: " + p.getFileName());
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long n = buf.getInt(80) & 0xFFFFFFFFL;
        if (n <= 0 || data.length != 84 + n * 50)
            throw new IOException("바이너리 STL이 아닙니다: " + p.getFileName());
        for (int i = 0; i < n; i++) {
            int base = 84 + i * 50 + 12;
            for (int k = 0; k < 9; k++) {
                float v = buf.getFloat(base + k * 4);
                buf.putFloat(base + k * 4, (float) (v * s));
            }
        }
        Files.write(p, data);
    }

    static void scaleAsciiStl(Path p, double s) throws IOException {
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("vertex")) {
                List<String> keep = new ArrayList<>();
                for (String x : t.split(" ")) if (!x.isEmpty()) keep.add(x);
                if (keep.size() >= 4) {
                    sb.append("vertex ");
                    for (int k = 1; k <= 3; k++) {
                        sb.append(Double.parseDouble(keep.get(k)) * s);
                        if (k < 3) sb.append(' ');
                    }
                    sb.append('\n');
                    continue;
                }
            }
            sb.append(line).append('\n');
        }
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
    }

    static void mergeBinaryStls(List<Path> inputs, Path out) throws IOException {
        long total = 0;
        for (Path p : inputs) {
            long len = Files.size(p);
            if (len < 84) throw new IOException("손상된 STL(크기 부족): " + p.getFileName());
            long n = triangleCount(p);
            if (n < 0 || len != 84 + n * 50)
                throw new IOException("바이너리 STL이 아닙니다: " + p.getFileName());
            total += n;
        }
        if (total > 0xFFFFFFFFL) throw new IOException("삼각형 수가 STL 형식 한계 초과: " + total);
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
            byte[] header = new byte[80];
            byte[] tag = "merged by STL Generator (Java)".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(tag, 0, header, 0, tag.length);
            os.write(header);
            int c32 = (int) total;
            os.write(c32 & 0xff);
            os.write((c32 >>> 8) & 0xff);
            os.write((c32 >>> 16) & 0xff);
            os.write((c32 >>> 24) & 0xff);
            byte[] buf = new byte[65536];
            for (Path p : inputs) {
                long remain = Files.size(p) - 84;
                try (InputStream in = new BufferedInputStream(Files.newInputStream(p))) {
                    long skipped = 0;
                    while (skipped < 84) {
                        long s = in.skip(84 - skipped);
                        if (s <= 0) throw new IOException("STL 헤더 건너뛰기 실패: " + p.getFileName());
                        skipped += s;
                    }
                    while (remain > 0) {
                        int r = in.read(buf, 0, (int) Math.min(buf.length, remain));
                        if (r < 0) throw new IOException("STL 본문 읽기 실패: " + p.getFileName());
                        os.write(buf, 0, r);
                        remain -= r;
                    }
                }
            }
        }
    }

    static void deleteRecursively(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(pp -> {
                try {
                    Files.deleteIfExists(pp);
                } catch (IOException e) {
                    System.err.println("임시 파일 삭제 실패: " + pp);
                }
            });
        } catch (IOException e) {
            System.err.println("임시 디렉터리 정리 실패: " + dir);
        }
    }

    // =========================================================================
    //  HTTP 핸들러
    // =========================================================================
    static void handleRoot(HttpExchange ex) {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, "text/plain; charset=utf-8", "POST only".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 200, "text/html; charset=utf-8", HTML.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("UI 응답 실패: " + e);
        }
    }

    static void handleEnv(HttpExchange ex) {
        try {
            jsonOk(ex, "{\"ok\":true,\"openscad\":" + json(openScadPath == null ? "" : openScadPath) + "}");
        } catch (Exception e) {
            jsonError(ex, "서버 오류: " + e);
        }
    }

    static void handleLoad(HttpExchange ex) {
        try {
            Map<String, String> m = params(readBody(ex));
            String pathS = cleanPath(m.getOrDefault("path", ""));
            if (pathS.isEmpty()) {
                jsonError(ex, "파일 경로를 입력하세요.");
                return;
            }
            Path f = Paths.get(pathS).toAbsolutePath().normalize();
            if (!Files.isRegularFile(f)) {
                jsonError(ex, "파일을 찾을 수 없습니다: " + pathS);
                return;
            }
            requireScadFile(f);
            jsonOk(ex, buildLoadJson(f));
        } catch (Exception e) {
            jsonError(ex, "파일 분석 실패: " + e.getMessage());
        }
    }

    /** 브라우저 파일 대화상자/드래그&드롭으로 올린 SCAD를 uploads 폴더에 저장 후 분석 */
    static void handleUpload(HttpExchange ex) {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                jsonError(ex, "POST만 허용됩니다.");
                return;
            }
            Map<String, String> q = params(ex.getRequestURI().getRawQuery());
            String fn = sanitizeFileName(q.getOrDefault("name", ""));
            if (fn.isBlank()) fn = "model.scad";
            if (!fn.toLowerCase(Locale.ROOT).endsWith(".scad")) fn = fn + ".scad";
            byte[] data = readBodyBytes(ex);
            if (data.length == 0) {
                jsonError(ex, "빈 파일입니다.");
                return;
            }
            if (data.length > 5L * 1024 * 1024) {
                jsonError(ex, "파일이 너무 큽니다(5MB 초과).");
                return;
            }
            Path dir = Paths.get(System.getProperty("user.dir"), "uploads");
            Files.createDirectories(dir);
            Path target = dir.resolve(fn).normalize();
            if (!target.startsWith(dir)) {
                jsonError(ex, "잘못된 파일명입니다.");
                return;
            }
            Files.write(target, data);
            requireScadFile(target);
            jsonOk(ex, buildLoadJson(target));
        } catch (Exception e) {
            jsonError(ex, "업로드 실패: " + e.getMessage());
        }
    }

    static void requireScadFile(Path f) throws IOException {
        String fileName = f.getFileName() == null ? "" : f.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".scad"))
            throw new IOException(".scad 파일만 불러올 수 있습니다: " + fileName);
        if (Files.size(f) > 5L * 1024 * 1024)
            throw new IOException("파일이 너무 큽니다(5MB 초과).");
    }

    static byte[] readBodyBytes(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return is.readAllBytes();
        }
    }

    /** SCAD 분석 공통: 부재 감지(PART/module) + 코드 원문을 응답 JSON으로 구성 */
    static String buildLoadJson(Path f) throws IOException {
        String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        List<String> parts = parseParts(src);
        String mode;
        StringBuilder units = new StringBuilder("[");
        if (parts.size() >= 2) {
            mode = "part";
            Map<String, Integer> counts = parsePartCounts(src);
            boolean first = true;
            for (String p : parts) {
                int cnt = counts.getOrDefault(p, -1);
                units.append(first ? "" : ",")
                        .append("{\"name\":").append(json(p)).append(",\"count\":").append(cnt).append("}");
                first = false;
            }
        } else {
            mode = "module";
            List<String> mods = parseModules(src);
            boolean first = true;
            for (String mn : mods) {
                units.append(first ? "" : ",")
                        .append("{\"name\":").append(json(mn)).append(",\"count\":-1}");
                first = false;
            }
        }
        units.append(']');

        recordScad(f.toString());
        return "{\"ok\":true,\"path\":" + json(f.toString())
                + ",\"base\":" + json(baseName(f))
                + ",\"mode\":" + json(mode)
                + ",\"code\":" + json(src)
                + ",\"units\":" + units + "}";
    }

    static void handleBrowse(HttpExchange ex) {
        try {
            Map<String, String> m = params(readBody(ex));
            String special = m.getOrDefault("special", "").trim();
            String pathS = cleanPath(m.getOrDefault("path", ""));
            String child = m.getOrDefault("child", "").trim();
            Path dir;
            if (!special.isEmpty()) {
                String home = System.getProperty("user.home");
                String target = switch (special) {
                    case "desktop" -> home + File.separator + "Desktop";
                    case "documents" -> home + File.separator + "Documents";
                    case "home" -> home;
                    default -> "";
                };
                if (target.isEmpty() || !Files.isDirectory(Paths.get(target))) {
                    jsonError(ex, "특수 폴더를 찾을 수 없습니다: " + special);
                    return;
                }
                dir = Paths.get(target);
            } else {
                if (pathS.isEmpty()) pathS = System.getProperty("user.home");
                dir = Paths.get(pathS).toAbsolutePath().normalize();
                if (!child.isEmpty()) dir = dir.resolve(child).normalize();
            }
            if (!Files.isDirectory(dir)) {
                jsonError(ex, "폴더가 아닙니다: " + dir);
                return;
            }
            List<String> dirs = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isDirectory).forEach(pp -> {
                    Path fn = pp.getFileName();
                    if (fn != null) dirs.add(fn.toString());
                });
            }
            dirs.sort(String.CASE_INSENSITIVE_ORDER);
            List<String> drives = new ArrayList<>();
            for (File r : File.listRoots()) drives.add(r.toString());
            Path parent = dir.getParent();
            String j = "{\"ok\":true,\"current\":" + json(dir.toString())
                    + ",\"parent\":" + json(parent == null ? "" : parent.toString())
                    + ",\"dirs\":" + jarr(dirs)
                    + ",\"drives\":" + jarr(drives) + "}";
            jsonOk(ex, j);
        } catch (Exception e) {
            jsonError(ex, "폴더 탐색 실패: " + e.getMessage());
        }
    }

    static void handleMkdir(HttpExchange ex) {
        try {
            Map<String, String> m = params(readBody(ex));
            String parentS = cleanPath(m.getOrDefault("path", ""));
            String name = m.getOrDefault("name", "").trim().replaceAll("[\\\\/:*?\"<>|]", "_");
            if (parentS.isEmpty()) {
                jsonError(ex, "상위 폴더 경로가 필요합니다.");
                return;
            }
            if (name.isBlank()) {
                jsonError(ex, "폴더 이름이 필요합니다.");
                return;
            }
            Path parent = Paths.get(parentS).toAbsolutePath().normalize();
            if (!Files.isDirectory(parent)) {
                jsonError(ex, "상위 폴더가 없습니다: " + parent);
                return;
            }
            Path created = parent.resolve(name);
            try {
                Files.createDirectories(created);
            } catch (IOException e) {
                jsonError(ex, "폴더 생성 실패: " + e.getMessage());
                return;
            }
            jsonOk(ex, "{\"ok\":true,\"current\":" + json(created.toString()) + "}");
        } catch (Exception e) {
            jsonError(ex, "폴더 생성 실패: " + e.getMessage());
        }
    }

    static void handleExport(HttpExchange ex) {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                jsonError(ex, "POST만 허용됩니다.");
                return;
            }
            Map<String, String> m = params(readBody(ex));
            String scadS = cleanPath(m.getOrDefault("scad", ""));
            String folderS = cleanPath(m.getOrDefault("folder", ""));
            String mode = m.getOrDefault("mode", "merge");
            String kind = m.getOrDefault("kind", "module");
            String unitsS = m.getOrDefault("units", "");
            String nameS = m.getOrDefault("name", "").trim();

            if (scadS.isEmpty() || folderS.isEmpty()) {
                jsonError(ex, "SCAD 경로와 저장 폴더가 필요합니다.");
                return;
            }
            if (!mode.equals("merge") && !mode.equals("split")) {
                jsonError(ex, "mode는 merge 또는 split이어야 합니다.");
                return;
            }
            boolean isPart = kind.equals("part");
            List<String[]> units = new ArrayList<>(); // {name, idx}
            for (String u : unitsS.split(",")) {
                u = u.trim();
                if (u.isEmpty()) continue;
                int c = u.lastIndexOf(':');
                if (c < 0) {
                    jsonError(ex, "잘못된 부재 형식: " + u);
                    return;
                }
                String nm = u.substring(0, c);
                String idxS = u.substring(c + 1);
                if (isPart ? !PART_NAME.matcher(nm).matches() : !MODULE_NAME.matcher(nm).matches()) {
                    jsonError(ex, "허용되지 않는 부재 이름: " + nm);
                    return;
                }
                int idx = -1;
                if (isPart && !idxS.isEmpty()) {
                    try {
                        idx = Integer.parseInt(idxS);
                    } catch (NumberFormatException e) {
                        jsonError(ex, "IDX는 정수여야 합니다: " + nm);
                        return;
                    }
                    if (idx < -1) {
                        jsonError(ex, "IDX는 비어 있거나(전체) 0 이상이어야 합니다: " + nm);
                        return;
                    }
                }
                units.add(new String[]{nm, String.valueOf(idx)});
            }
            if (units.isEmpty()) {
                jsonError(ex, "내보낼 부재가 없습니다.");
                return;
            }
            if (openScadPath == null) {
                jsonError(ex, "OpenSCAD를 찾지 못했습니다. Program Files 설치 또는 PATH 등록이 필요합니다.");
                return;
            }
            Path scad = Paths.get(scadS).toAbsolutePath().normalize();
            if (!Files.isRegularFile(scad)) {
                jsonError(ex, "SCAD 파일을 찾을 수 없습니다: " + scad);
                return;
            }
            Path folder = Paths.get(folderS).toAbsolutePath().normalize();
            try {
                Files.createDirectories(folder);
            } catch (IOException e) {
                jsonError(ex, "저장 폴더 생성 실패: " + e.getMessage());
                return;
            }

            boolean merge = mode.equals("merge");
            boolean enumerate = isPart && !merge && m.getOrDefault("enum", "").equals("1");
            double scaleValue = 1.0;
            String scaleS = m.getOrDefault("scale", "").trim();
            if (!scaleS.isEmpty()) {
                try {
                    scaleValue = Double.parseDouble(scaleS);
                } catch (NumberFormatException e) {
                    jsonError(ex, "크기 비율은 숫자여야 합니다.");
                    return;
                }
                if (scaleValue <= 0 || scaleValue > 1000) {
                    jsonError(ex, "크기 비율은 0보다 크고 1000 이하여야 합니다.");
                    return;
                }
            }
            final double scale = scaleValue;
            recordFolder(folder.toString());
            String base = baseName(scad);
            String outBase = nameS.isEmpty() ? base + "_merged" : sanitizeFileName(nameS);

            Job job = new Job("job-" + JOB_SEQ.incrementAndGet());
            job.folder = folder.toString();
            JOBS.put(job.id, job);
            evictJobs();
            Thread worker = new Thread(() ->
                    executeExport(job, scad, folder, merge, isPart, units, outBase, enumerate, scale));
            worker.setDaemon(true);
            worker.start();
            jsonOk(ex, "{\"ok\":true,\"jobId\":" + json(job.id) + "}");
        } catch (Exception e) {
            jsonError(ex, "내보내기 시작 실패: " + e.getMessage());
        }
    }

    /**
     * 백그라운드 내보내기 실행: (IDX 열거 확장) → 부재별 렌더 → 병합 또는 폴더 이동.
     * 진행률·결과는 job에 기록하고 브라우저는 /api/job 으로 폴링한다.
     */
    static void executeExport(Job job, Path scad, Path folder, boolean merge,
                              boolean isPart, List<String[]> units, String outBase,
                              boolean enumerate, double scale) {
        List<String[]> runs = units;
        if (enumerate) {
            Map<String, Integer> counts;
            try {
                counts = parsePartCounts(new String(Files.readAllBytes(scad), StandardCharsets.UTF_8));
            } catch (IOException ioe) {
                counts = Map.of();
            }
            runs = new ArrayList<>();
            for (String[] u : units) {
                if (u[1].equals("-1")) {
                    Integer c = counts.get(u[0]);
                    if (c != null && c > 0) {
                        for (int k = 0; k < c; k++) runs.add(new String[]{u[0], String.valueOf(k)});
                        continue;
                    }
                }
                runs.add(u);
            }
        }
        if (runs.size() > MAX_RUNS_PER_JOB) {
            job.status = "error";
            job.error = "렌더 수가 너무 많습니다(" + runs.size() + "개). 최대 " + MAX_RUNS_PER_JOB + "개까지 가능합니다.";
            return;
        }
        job.total = runs.size() + ((merge && runs.size() > 1) ? 1 : 0);
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("stlgen");
            List<Path> produced = new ArrayList<>();
            if (isPart) {
                boolean singleDirect = merge && runs.size() == 1;
                for (String[] u : runs) {
                    if (job.cancelRequested) { job.status = "cancelled"; return; }
                    String nm = u[0];
                    int idx = Integer.parseInt(u[1]);
                    String outName = idx >= 0 ? nm + "_idx" + idx + ".stl" : nm + ".stl";
                    Path out = singleDirect ? folder.resolve(outBase + ".stl") : tmp.resolve(outName);
                    job.current = outName + " 렌더 중";
                    List<String> cmd = exportCmd(out);
                    cmd.add("-D");
                    cmd.add("PART=\\\"" + nm + "\\\"");
                    if (idx >= 0) { cmd.add("-D"); cmd.add("IDX=" + idx); }
                    cmd.add(scad.toString());
                    CmdResult r = runCommand(cmd, OPENSCAD_TIMEOUT_SEC, p -> job.currentProcess = p);
                    job.currentProcess = null;
                    boolean ok = r.code() == 0 && Files.exists(out) && !r.timeout();
                    if (ok && scale != 1.0) {
                        try {
                            scaleStl(out, scale);
                        } catch (IOException se) {
                            ok = false;
                            job.allOk = false;
                            r = new CmdResult(r.code(), "크기 비율 적용 실패: " + se.getMessage() + "\n" + r.output(), r.timeout());
                        }
                    }
                    job.allOk &= ok;
                    String logText = (scale != 1.0 ? "비율 x" + scale + " 적용\n" : "") + r.output();
                    job.rows.add(resultRow(outName, ok, out, logText));
                    produced.add(out);
                    job.done++;
                }
            } else {
                boolean singleDirect = merge && runs.size() == 1;
                for (String[] u : runs) {
                    if (job.cancelRequested) { job.status = "cancelled"; return; }
                    String mn = u[0];
                    String outName = mn + ".stl";
                    Path out = singleDirect ? folder.resolve(outBase + ".stl") : tmp.resolve(outName);
                    Path w = tmp.resolve(mn + ".scad");
                    Files.writeString(w, buildWrapper(scad, List.of(mn)), StandardCharsets.UTF_8);
                    job.current = outName + " 렌더 중";
                    List<String> cmd = exportCmd(out);
                    cmd.add(w.toString());
                    CmdResult r = runCommand(cmd, OPENSCAD_TIMEOUT_SEC, p -> job.currentProcess = p);
                    job.currentProcess = null;
                    boolean ok = r.code() == 0 && Files.exists(out) && !r.timeout();
                    if (ok && scale != 1.0) {
                        try {
                            scaleStl(out, scale);
                        } catch (IOException se) {
                            ok = false;
                            job.allOk = false;
                            r = new CmdResult(r.code(), "크기 비율 적용 실패: " + se.getMessage() + "\n" + r.output(), r.timeout());
                        }
                    }
                    job.allOk &= ok;
                    String logText = (scale != 1.0 ? "비율 x" + scale + " 적용\n" : "") + r.output();
                    job.rows.add(resultRow(outName, ok, out, logText));
                    produced.add(out);
                    job.done++;
                }
            }
            if (job.cancelRequested) { job.status = "cancelled"; return; }
            if (merge && runs.size() > 1) {
                job.current = "STL 병합 중";
                Path finalOut = folder.resolve(outBase + ".stl");
                try {
                    mergeStls(produced, finalOut);
                    job.rows.add(resultRow(finalOut.getFileName().toString(),
                            job.allOk && Files.exists(finalOut), finalOut,
                            runs.size() + "개 STL 병합 완료"));
                } catch (IOException ioe) {
                    job.allOk = false;
                    job.rows.add(errorRow(finalOut.getFileName().toString(), "병합 실패: " + ioe.getMessage()));
                }
                job.done++;
            } else if (!merge) {
                for (Path producedFile : produced) {
                    Path dst = folder.resolve(producedFile.getFileName().toString());
                    try {
                        Files.move(producedFile, dst, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ioe) {
                        job.allOk = false;
                        job.rows.add(errorRow(dst.getFileName().toString(), "파일 이동 실패: " + ioe.getMessage()));
                    }
                }
            }
            job.current = "";
            job.status = "done";
        } catch (Exception e) {
            job.status = "error";
            job.error = String.valueOf(e.getMessage());
        } finally {
            if (tmp != null) deleteRecursively(tmp);
        }
    }

    static void handleOpenFolder(HttpExchange ex) {
        try {
            Map<String, String> m = params(readBody(ex));
            String pathS = cleanPath(m.getOrDefault("path", ""));
            if (pathS.isEmpty()) {
                jsonError(ex, "폴더 경로가 필요합니다.");
                return;
            }
            Path dir = Paths.get(pathS).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                jsonError(ex, "폴더가 없습니다: " + dir);
                return;
            }
            boolean opened = false;
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop d = Desktop.getDesktop();
                    if (d.isSupported(Desktop.Action.OPEN)) {
                        d.open(dir.toFile());
                        opened = true;
                    }
                }
            } catch (Exception de) {
                opened = false;
            }
            if (!opened) {
                try {
                    new ProcessBuilder("explorer.exe", dir.toString()).start();
                    opened = true;
                } catch (IOException pe) {
                    jsonError(ex, "폴더 열기 실패: " + pe.getMessage());
                    return;
                }
            }
            jsonOk(ex, "{\"ok\":true}");
        } catch (Exception e) {
            jsonError(ex, "폴더 열기 실패: " + e.getMessage());
        }
    }

    // =========================================================================
    //  비동기 작업 큐 / 최근 기록 / 미리보기 (v2)
    // =========================================================================
    static class Job {
        final String id;
        volatile String status = "running"; // running | done | cancelled | error
        volatile int total = 0;
        volatile int done = 0;
        volatile String current = "";
        volatile boolean allOk = true;
        volatile String folder = "";
        volatile String error = "";
        volatile boolean cancelRequested = false;
        volatile Process currentProcess = null;
        final List<String> rows = Collections.synchronizedList(new ArrayList<>());

        Job(String id) { this.id = id; }
    }

    static void evictJobs() {
        if (JOBS.size() <= 30) return;
        JOBS.entrySet().removeIf(e -> !"running".equals(e.getValue().status));
    }

    static void handleJobStatus(HttpExchange ex) {
        try {
            Map<String, String> q = params(ex.getRequestURI().getRawQuery());
            Job job = JOBS.get(q.getOrDefault("id", ""));
            if (job == null) {
                jsonError(ex, "작업을 찾을 수 없습니다.");
                return;
            }
            List<String> snapshot;
            synchronized (job.rows) {
                snapshot = new ArrayList<>(job.rows);
            }
            StringBuilder rowsJson = new StringBuilder("[");
            for (int i = 0; i < snapshot.size(); i++) {
                if (i > 0) rowsJson.append(',');
                rowsJson.append(snapshot.get(i));
            }
            rowsJson.append(']');
            String j = "{\"ok\":true,\"status\":" + json(job.status)
                    + ",\"done\":" + job.done
                    + ",\"total\":" + job.total
                    + ",\"current\":" + json(job.current)
                    + ",\"allOk\":" + job.allOk
                    + ",\"folder\":" + json(job.folder)
                    + ",\"error\":" + json(job.error)
                    + ",\"results\":" + rowsJson + "}";
            jsonOk(ex, j);
        } catch (Exception e) {
            jsonError(ex, "작업 상태 조회 실패: " + e.getMessage());
        }
    }

    static void handleCancel(HttpExchange ex) {
        try {
            Map<String, String> m = params(readBody(ex));
            Job job = JOBS.get(m.getOrDefault("id", ""));
            if (job == null) {
                jsonError(ex, "작업을 찾을 수 없습니다.");
                return;
            }
            job.cancelRequested = true;
            Process cp = job.currentProcess;
            if (cp != null) cp.destroyForcibly();
            jsonOk(ex, "{\"ok\":true}");
        } catch (Exception e) {
            jsonError(ex, "작업 취소 실패: " + e.getMessage());
        }
    }

    static void handlePreview(HttpExchange ex) {
        try {
            Map<String, String> q = params(ex.getRequestURI().getRawQuery());
            String pathS = cleanPath(q.getOrDefault("path", ""));
            Path f = pathS.isEmpty() ? null : Paths.get(pathS).toAbsolutePath().normalize();
            String fn = (f == null || f.getFileName() == null) ? "" : f.getFileName().toString().toLowerCase(Locale.ROOT);
            if (f == null || !Files.isRegularFile(f) || !fn.endsWith(".stl")) {
                send(ex, 404, "text/plain; charset=utf-8", "STL 파일이 없습니다.".getBytes(StandardCharsets.UTF_8));
                return;
            }
            long size = Files.size(f);
            if (size > 200L * 1024 * 1024) {
                send(ex, 413, "text/plain; charset=utf-8", "파일이 너무 큽니다(200MB 초과).".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 200, "application/octet-stream", Files.readAllBytes(f));
        } catch (Exception e) {
            try {
                send(ex, 500, "text/plain; charset=utf-8",
                        ("미리보기 오류: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
            } catch (IOException ioe) {
                System.err.println("미리보기 응답 실패: " + ioe.getMessage());
            }
        }
    }

    static void handleSettings(HttpExchange ex) {
        try {
            String scads, folders;
            synchronized (SETTINGS_LOCK) {
                scads = jarr(recentScads);
                folders = jarr(recentFolders);
            }
            jsonOk(ex, "{\"ok\":true,\"scads\":" + scads + ",\"folders\":" + folders + "}");
        } catch (Exception e) {
            jsonError(ex, "설정 조회 실패: " + e.getMessage());
        }
    }

    static Path settingsFile() {
        return Paths.get(System.getProperty("user.dir"), "stlgen_settings.txt");
    }

    static void loadSettings() {
        synchronized (SETTINGS_LOCK) {
            recentScads.clear();
            recentFolders.clear();
            if (!Files.exists(settingsFile())) return;
            try {
                String section = "";
                for (String line : Files.readAllLines(settingsFile(), StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.equals("[scad]")) { section = "scad"; continue; }
                    if (t.equals("[folder]")) { section = "folder"; continue; }
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    if (section.equals("scad") && recentScads.size() < 5) recentScads.add(t);
                    else if (section.equals("folder") && recentFolders.size() < 5) recentFolders.add(t);
                }
            } catch (IOException e) {
                System.err.println("설정 파일 읽기 실패: " + e.getMessage());
            }
        }
    }

    static void recordScad(String path) {
        recordRecent(recentScads, path);
    }

    static void recordFolder(String path) {
        recordRecent(recentFolders, path);
    }

    static void recordRecent(List<String> list, String value) {
        synchronized (SETTINGS_LOCK) {
            list.removeIf(v -> v.equalsIgnoreCase(value));
            list.add(0, value);
            while (list.size() > 5) list.remove(list.size() - 1);
            StringBuilder sb = new StringBuilder("# STL Generator 최근 기록\n[scad]\n");
            for (String s : recentScads) sb.append(s).append('\n');
            sb.append("[folder]\n");
            for (String s : recentFolders) sb.append(s).append('\n');
            try {
                Files.writeString(settingsFile(), sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("설정 파일 저장 실패: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    //  결과 행 / 공용 유틸
    // =========================================================================
    static String resultRow(String displayName, boolean ok, Path file, String logText) {
        long size = -1;
        try {
            if (file != null && Files.exists(file)) size = Files.size(file);
        } catch (IOException e) {
            System.err.println("파일 크기 확인 실패: " + file);
        }
        return "{\"name\":" + json(displayName)
                + ",\"ok\":" + ok
                + ",\"size\":" + size
                + ",\"log1\":" + json(firstLine(logText))
                + ",\"log\":" + json(cap(logText, 4000)) + "}";
    }

    static String errorRow(String displayName, String msg) {
        return "{\"name\":" + json(displayName) + ",\"ok\":false,\"size\":-1,\"log1\":"
                + json(msg) + ",\"log\":\"\"}";
    }

    static String json(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<' -> sb.append("\\u003c");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    static String jarr(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(json(items.get(i)));
        }
        return sb.append(']').toString();
    }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    static void jsonOk(HttpExchange ex, String body) {
        try {
            send(ex, 200, JSON_TYPE, body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("응답 전송 실패: " + e.getMessage());
        }
    }

    static void jsonError(HttpExchange ex, String msg) {
        jsonOk(ex, "{\"ok\":false,\"error\":" + json(msg == null ? "오류" : msg) + "}");
    }

    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Map<String, String> params(String body) {
        Map<String, String> map = new HashMap<>();
        for (String kv : body.split("&")) {
            int eq = kv.indexOf('=');
            try {
                if (eq < 0) map.put(URLDecoder.decode(kv, StandardCharsets.UTF_8), "");
                else map.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException badEscape) {
                // 인코딩이 깨진 파라미터는 무시
            }
        }
        return map;
    }

    static String cleanPath(String s) {
        s = s == null ? "" : s.trim();
        if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1).trim();
        return s;
    }

    static String sanitizeFileName(String s) {
        return s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    static String baseName(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    static String firstLine(String s) {
        if (s == null) return "";
        String t = s.trim();
        int idx = t.indexOf('\n');
        return idx >= 0 ? t.substring(0, idx) : t;
    }

    static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + " ...(이하 생략)";
    }

    // =========================================================================
    //  UI (내장 HTML + CSS + JS)
    // =========================================================================
    static final String HTML = """
            <!DOCTYPE html>
            <html lang="ko">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>STL Generator — OpenSCAD 부재 내보내기</title>
            <style>
            :root { --bg:#f3f5f9; --panel:#ffffff; --line:#dfe4ee; --ink:#1c2430; --sub:#5c6b82;
                    --accent:#2f5ce6; --accent-dk:#2448b8; --ok:#128a3e; --err:#c93030; --warn:#b07a12; }
            * { box-sizing:border-box; }
            body { margin:0; font-family:'Segoe UI','Malgun Gothic','Apple SD Gothic Neo',sans-serif;
                   background:var(--bg); color:var(--ink); }
            header { display:flex; justify-content:space-between; align-items:center;
                     padding:16px 28px; background:var(--panel); border-bottom:1px solid var(--line); }
            header h1 { margin:0; font-size:21px; }
            header p { margin:3px 0 0; color:var(--sub); font-size:13px; }
            .badge { padding:6px 14px; border-radius:999px; font-size:13px; font-weight:600;
                     background:#eef1f7; color:var(--sub); }
            .badge.ok { background:#e3f6e9; color:var(--ok); }
            .badge.err { background:#fbe5e5; color:var(--err); }
            main { display:grid; grid-template-columns:1fr 1fr; gap:18px; padding:18px 28px 0;
                   max-width:1400px; margin:0 auto; }
            @media (max-width:980px) { main { grid-template-columns:1fr; } }
            .panel { background:var(--panel); border:1px solid var(--line); border-radius:12px;
                     padding:18px 20px; }
            #resultPanel, .logwrap { margin:18px 28px; max-width:1344px; }
            @media (max-width:980px) { #resultPanel, .logwrap { margin:18px 12px; } }
            @media (max-width:980px) { main { padding:12px 12px 0; } }
            h2 { font-size:15px; margin:20px 0 10px; display:flex; align-items:center; gap:10px; }
            .panel > h2:first-child { margin-top:0; }
            h3 { font-size:13px; margin:14px 0 6px; color:var(--sub); }
            .row { display:flex; gap:8px; margin:6px 0; align-items:center; }
            .row input[type=text] { flex:1; min-width:0; }
            input[type=text] { padding:9px 12px; border:1px solid var(--line); border-radius:8px; font-size:14px; }
            input[type=text]:focus { outline:none; border-color:var(--accent);
                                     box-shadow:0 0 0 3px rgba(47,92,230,.15); }
            button { padding:9px 16px; border:1px solid var(--line); background:#fff; border-radius:8px;
                     font-size:14px; cursor:pointer; white-space:nowrap; }
            button:hover { background:#f4f6fb; }
            button.primary { background:var(--accent); border-color:var(--accent-dk); color:#fff; font-weight:600; }
            button.primary:hover { background:var(--accent-dk); }
            button.big { padding:12px 22px; font-size:15px; width:100%; }
            button:disabled { opacity:.55; cursor:not-allowed; }
            button.linklike { border:none; background:none; color:var(--accent); font-size:13px; padding:0; }
            .hint { color:var(--sub); font-size:12.5px; margin:4px 0 0; }
            code { background:#eef1f7; padding:1px 6px; border-radius:4px; font-size:12px; }
            .count { font-size:12px; background:#eef1f7; padding:2px 10px; border-radius:999px; color:var(--sub); }
            textarea { width:100%; height:220px; resize:vertical; border:1px solid var(--line);
                       border-radius:8px; padding:10px 12px; font-family:Consolas,monospace;
                       font-size:12.5px; line-height:1.5; background:#fbfcfe; }
            .mode-note { font-size:12.5px; color:var(--accent-dk); background:#eef3ff;
                         border-radius:8px; padding:8px 12px; margin:8px 0; }
            .unit-list { max-height:250px; overflow:auto; border:1px solid var(--line);
                         border-radius:8px; padding:4px; }
            .mod-item { display:flex; gap:10px; align-items:center; padding:6px 10px;
                        border-radius:6px; font-size:14px; }
            .mod-item:hover { background:#f2f5fb; }
            .mod-item .chk { display:flex; gap:9px; align-items:center; flex:1; cursor:pointer; min-width:0; }
            .mod-item .chk span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
            .mod-item input[type=checkbox] { width:16px; height:16px; accent-color:var(--accent); flex:none; }
            .idx-input { width:90px; flex:none; padding:4px 8px; font-size:13px; text-align:right; }
            .radio-row { display:flex; flex-direction:column; gap:8px; margin:6px 0; }
            .radio-row label { font-size:14px; display:flex; gap:8px; align-items:center; cursor:pointer; }
            .lbl { font-size:13px; color:var(--sub); min-width:80px; flex:none; }
            table { width:100%; border-collapse:collapse; font-size:13.5px; }
            th, td { text-align:left; padding:8px 10px; border-bottom:1px solid var(--line); }
            th { color:var(--sub); font-size:12.5px; font-weight:600; background:#f7f9fc; }
            td.ok { color:var(--ok); font-weight:600; }
            td.err { color:var(--err); font-weight:600; }
            .log { max-height:160px; overflow:auto; background:#101623; color:#c9d6ee;
                   font-family:Consolas,monospace; font-size:12px; padding:12px 16px;
                   border-radius:12px; }
            .log .ok { color:#7ee2a0; }
            .log .err { color:#ff9a9a; }
            .log .warn { color:#ffd48a; }
            .modal-back { position:fixed; inset:0; background:rgba(15,22,40,.45); display:flex;
                          align-items:center; justify-content:center; z-index:50; }
            .modal { background:#fff; border-radius:14px; padding:20px 22px; width:560px; max-width:94vw; }
            .modal h3 { margin:0 0 10px; color:var(--ink); font-size:16px; }
            .drives { display:flex; gap:6px; flex-wrap:wrap; margin:8px 0; }
            .drives button, .quickrow button { font-size:13px; padding:6px 12px; }
            .quickrow { display:flex; gap:6px; margin:4px 0; }
            .dir-list { height:250px; overflow:auto; border:1px solid var(--line); border-radius:8px; margin:8px 0; }
            .dir-item { display:block; width:100%; text-align:left; padding:8px 12px; border:none;
                        background:none; cursor:pointer; font-size:14px;
                        border-bottom:1px solid #f0f2f7; border-radius:0; }
            .dir-item:hover { background:#f2f5fb; }
            .row.end { justify-content:flex-end; margin-top:12px; }
            .progress-wrap { margin:14px 0 4px; }
            .progress-bar-bg { height:14px; background:#e8ecf4; border-radius:999px; overflow:hidden; border:1px solid var(--line); }
            .progress-bar { height:100%; width:0%; background:linear-gradient(90deg,#4c7dff,#2f5ce6); transition:width .3s; }
            .progress-text { font-size:13px; color:var(--sub); }
            .chips { display:flex; gap:6px; flex-wrap:wrap; margin:6px 0 2px; }
            .chip { font-size:12px; padding:4px 10px; background:#eef1f7; border:1px solid var(--line);
                    border-radius:999px; cursor:pointer; max-width:400px; overflow:hidden;
                    text-overflow:ellipsis; white-space:nowrap; }
            .chip:hover { background:#e2e8f5; }
            .enum-row { display:flex; gap:8px; align-items:center; margin:8px 0; font-size:13.5px; }
            .enum-row input { accent-color:var(--accent); width:15px; height:15px; }
            #scadPanel.dragover { outline:2px dashed var(--accent); outline-offset:-6px; background:#f0f4ff; }
            .view-modal { width:860px; max-width:96vw; }
            #viewCanvas { width:100%; height:480px; display:block; background:#141a26;
                          border-radius:10px; cursor:grab; }
            .btn-mini { padding:3px 10px; font-size:12px; }
            .cancel-btn { color:var(--err); border-color:#e7b6b6; }
            .grow { flex:1; }
            [hidden] { display:none !important; }
            </style>
            </head>
            <body>
            <header>
              <div>
                <h1>⚙ STL Generator</h1>
                <p>OpenSCAD 코드에서 부재를 골라 STL로 내보내기 — 여러 부재를 하나의 파일로 병합 지원</p>
              </div>
              <div id="envBadge" class="badge">확인 중...</div>
            </header>

            <main>
              <section class="panel" id="scadPanel">
                <h2>1. SCAD 파일 불러오기</h2>
                <div class="row">
                  <input id="scadPath" type="text" placeholder="SCAD 파일 전체 경로 직접 입력, 또는 아래 방법 사용">
                  <button id="btnPick">📁 파일 선택</button>
                  <button id="btnLoad" class="primary">불러오기</button>
                </div>
                <input type="file" id="fileInput" accept=".scad" hidden>
                <div id="recentScads" class="chips" hidden></div>
                <p class="hint">SCAD 파일을 이 패널에 <b>끌어다 놓거나</b>, 📁 파일 선택으로 고르세요. 파일을 분석해 부재를 자동 감지합니다 (PART/IDX 플래그 방식 또는 module 방식).</p>
                <div id="modeNote" class="mode-note" hidden></div>
                <h3>코드 미리보기</h3>
                <textarea id="codeView" readonly spellcheck="false"
                          placeholder="SCAD 파일을 불러오면 코드가 여기에 표시됩니다."></textarea>
              </section>

              <section class="panel">
                <h2>2. 내보낼 부재 선택 <span id="unitCount" class="count">0개 선택</span></h2>
                <div class="row">
                  <button id="btnAll">전체 선택</button>
                  <button id="btnNone">전체 해제</button>
                </div>
                <div id="unitList" class="unit-list">
                  <p class="hint" style="padding:10px">SCAD 파일을 불러오면 부재 목록이 여기에 표시됩니다.</p>
                </div>

                <h2>3. 내보내기 설정</h2>
                <div class="radio-row">
                  <label><input type="radio" name="mode" value="merge" checked> 선택 부재를 <b>하나의 STL</b>로 병합</label>
                  <label><input type="radio" name="mode" value="split"> 부재마다 <b>개별 STL</b> 파일로</label>
                </div>
                <div class="row" id="mergeNameRow">
                  <span class="lbl">병합 파일명</span>
                  <input id="mergeName" type="text" placeholder="비우면 자동: 모델명_merged.stl">
                </div>
                <div class="row">
                  <span class="lbl">크기 비율</span>
                  <input id="scaleInput" type="text" value="1" style="max-width:110px">
                  <span class="hint">1=원본 · 0.5=절반 · 2=두 배 — 내보내는 모든 STL에 일괄 적용</span>
                </div>
                <div class="enum-row" id="enumRow" hidden>
                  <input type="checkbox" id="idxEnum">
                  <label for="idxEnum">개별 번호까지 전부 분리 (IDX 0 ~ N-1 · 개별 모드에서만 동작, 개수를 알 수 없는 부재는 통짜파일 1개)</label>
                </div>

                <h2>4. 저장 폴더</h2>
                <div class="row">
                  <input id="outFolder" type="text" placeholder="저장 폴더 (예: C:\\Users\\me\\Desktop\\output)">
                  <button id="btnBrowse">폴더 찾아보기</button>
                </div>
                <div class="row">
                  <button id="btnExport" class="primary big">⬇ STL 내보내기</button>
                </div>
                <div id="progressWrap" hidden>
                  <div class="progress-bar-bg"><div id="progressBar" class="progress-bar"></div></div>
                  <div class="row">
                    <span id="progressText" class="progress-text grow"></span>
                    <button id="btnCancelJob" class="cancel-btn" hidden>작업 취소</button>
                  </div>
                </div>
              </section>
            </main>

            <section class="panel" id="resultPanel">
              <h2>내보내기 결과 <button id="btnOpenFolder" class="linklike" hidden>📁 결과 폴더 열기</button></h2>
              <table id="resultTable">
                <thead><tr><th style="width:30%">파일</th><th style="width:11%">상태</th><th style="width:13%">크기</th><th>비고 (전체 로그: 행에 마우스 올림)</th><th style="width:7%">보기</th></tr></thead>
                <tbody></tbody>
              </table>
            </section>

            <div class="logwrap"><div id="log" class="log"></div></div>

            <div id="viewModal" class="modal-back" hidden>
              <div class="modal view-modal">
                <h3 id="viewTitle">3D 미리보기</h3>
                <canvas id="viewCanvas"></canvas>
                <div class="row">
                  <span id="viewInfo" class="hint grow"></span>
                  <button id="btnViewClose">닫기</button>
                </div>
                <p class="hint">드래그: 회전 · 휠: 확대/축소 · Shift+드래그: 이동</p>
              </div>
            </div>

            <div id="folderModal" class="modal-back" hidden>
              <div class="modal">
                <h3>저장 폴더 선택</h3>
                <div class="row">
                  <input id="browsePath" type="text">
                  <button id="btnGo">이동</button>
                  <button id="btnUp">상위</button>
                </div>
                <div class="quickrow">
                  <button class="quick" data-quick="home">홈</button>
                  <button class="quick" data-quick="desktop">바탕화면</button>
                  <button class="quick" data-quick="documents">문서</button>
                </div>
                <div class="hint" style="margin:6px 0 0">최근 저장 폴더:</div>
                <div id="recentFolders" class="chips"></div>
                <div id="driveRow" class="drives"></div>
                <div id="dirList" class="dir-list"></div>
                <div class="row">
                  <input id="newDirName" type="text" placeholder="새 폴더 이름">
                  <button id="btnMkdir">새 폴더 만들기</button>
                </div>
                <div class="row end">
                  <button id="btnPickFolder" class="primary">이 폴더로 지정</button>
                  <button id="btnCloseModal">취소</button>
                </div>
              </div>
            </div>

            <script>
            const $ = id => document.getElementById(id);
            let curScad = '', curBase = '', kind = 'module', units = [];
            let browseState = { path: '', parent: '' };
            let lastFolder = '';

            function log(msg, cls) {
              const d = document.createElement('div');
              if (cls) d.className = cls;
              d.textContent = msg;
              $('log').appendChild(d);
              $('log').scrollTop = $('log').scrollHeight;
            }

            async function post(url, data) {
              const r = await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: new URLSearchParams(data).toString()
              });
              return await r.json();
            }

            function sizeFmt(n) {
              if (n < 0) return '-';
              if (n < 1024) return n + ' B';
              if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
              return (n / 1048576).toFixed(2) + ' MB';
            }

            async function initEnv() {
              try {
                const r = await fetch('/api/env');
                const j = await r.json();
                const b = $('envBadge');
                if (j.openscad) {
                  b.textContent = 'OpenSCAD 연결됨';
                  b.className = 'badge ok';
                  log('OpenSCAD: ' + j.openscad, 'ok');
                } else {
                  b.textContent = 'OpenSCAD 미발견';
                  b.className = 'badge err';
                  log('OpenSCAD를 찾지 못했습니다. Program Files 설치 또는 PATH 등록이 필요합니다.', 'err');
                }
              } catch (e) {
                log('초기화 실패: ' + e, 'err');
              }
            }

            $('btnLoad').addEventListener('click', async () => {
              const path = $('scadPath').value.trim();
              if (!path) return alert('SCAD 파일 경로를 입력하거나, 파일 선택/드래그&드롭을 사용하세요.');
              const j = await post('/api/load', { path: path });
              applyLoaded(j);
            });

            function applyLoaded(j) {
              if (!j.ok) { log('불러오기 실패: ' + j.error, 'err'); alert(j.error); return; }
              curScad = j.path; curBase = j.base; kind = j.mode; units = j.units || [];
              $('codeView').value = j.code || '';
              $('modeNote').hidden = false;
              $('modeNote').textContent = kind === 'part'
                ? 'PART/IDX 플래그 방식 파일입니다. 행별 IDX 입력란: 비우면 그 종류 전체, 숫자면 해당 번호 1개만 내보냅니다.'
                : '모듈(module) 방식 파일입니다. 체크한 모듈을 wrapper로 조립해 STL 변환합니다.';
              $('mergeName').placeholder = '비우면 자동: ' + curBase + '_merged.stl';
              renderUnits();
              log('불러오기 완료: 부재 ' + units.length + '개 (' + j.path + ')', units.length ? 'ok' : 'warn');
              if (!units.length) alert('내보낼 부재를 찾지 못했습니다. 파일에 부재 정의가 있는지 확인하세요.');
              refreshSettings();
            }

            /* ---- 파일 선택 대화상자 / 드래그&드롭 업로드 ---- */
            $('btnPick').addEventListener('click', () => $('fileInput').click());
            $('fileInput').addEventListener('change', () => {
              const f = $('fileInput').files[0];
              $('fileInput').value = '';
              if (f) uploadScad(f);
            });

            async function uploadScad(file) {
              if (file.name.toLowerCase().slice(-5) !== '.scad') {
                return alert('SCAD 파일(.scad)만 불러올 수 있습니다: ' + file.name);
              }
              log('업로드 중: ' + file.name + ' (' + sizeFmt(file.size) + ')');
              try {
                const r = await fetch('/api/upload?name=' + encodeURIComponent(file.name), {
                  method: 'POST',
                  body: file
                });
                applyLoaded(await r.json());
              } catch (e) {
                log('업로드 실패: ' + e, 'err');
                alert('업로드 실패: ' + e);
              }
            }

            const dropZone = $('scadPanel');
            ['dragenter', 'dragover'].forEach(ev =>
              dropZone.addEventListener(ev, e => { e.preventDefault(); dropZone.classList.add('dragover'); }));
            ['dragleave', 'drop'].forEach(ev =>
              dropZone.addEventListener(ev, e => { e.preventDefault(); dropZone.classList.remove('dragover'); }));
            dropZone.addEventListener('drop', e => {
              const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
              if (f) uploadScad(f);
            });
            ['dragover', 'drop'].forEach(ev =>
              window.addEventListener(ev, e => e.preventDefault()));

            function updateCount() {
              const n = document.querySelectorAll('#unitList input[type=checkbox]:checked').length;
              $('unitCount').textContent = n + '개 선택';
            }

            function renderUnits() {
              const box = $('unitList');
              box.innerHTML = '';
              if (!units.length) {
                const p = document.createElement('p');
                p.className = 'hint';
                p.style.padding = '10px';
                p.textContent = 'SCAD 파일을 불러오면 부재 목록이 여기에 표시됩니다.';
                box.appendChild(p);
                updateCount();
                return;
              }
              units.forEach(u => {
                const row = document.createElement('div');
                row.className = 'mod-item';
                const lab = document.createElement('label');
                lab.className = 'chk';
                const cb = document.createElement('input');
                cb.type = 'checkbox';
                cb.checked = (kind === 'module');
                cb.dataset.name = u.name;
                cb.addEventListener('change', updateCount);
                const s = document.createElement('span');
                s.textContent = u.name + (u.count > 0 ? ' (' + u.count + '개)' : '');
                s.title = u.name;
                lab.appendChild(cb);
                lab.appendChild(s);
                row.appendChild(lab);
                if (kind === 'part') {
                  const idx = document.createElement('input');
                  idx.type = 'text';
                  idx.className = 'idx-input';
                  idx.placeholder = '전체';
                  idx.title = '비우면 이 종류 전체, 숫자 입력 시 해당 번호 1개만';
                  row.appendChild(idx);
                }
                box.appendChild(row);
              });
              $('enumRow').hidden = (kind !== 'part');
              syncEnumRow();
              updateCount();
            }

            function syncEnumRow() {
              const merge = document.querySelector('input[name=mode]:checked').value === 'merge';
              const en = $('idxEnum');
              if (merge || kind !== 'part') en.checked = false;
              en.disabled = merge || kind !== 'part';
            }

            $('btnAll').addEventListener('click', () => {
              document.querySelectorAll('#unitList input[type=checkbox]').forEach(cb => cb.checked = true);
              updateCount();
            });
            $('btnNone').addEventListener('click', () => {
              document.querySelectorAll('#unitList input[type=checkbox]').forEach(cb => cb.checked = false);
              updateCount();
            });

            document.querySelectorAll('input[name=mode]').forEach(r => r.addEventListener('change', () => {
              $('mergeNameRow').style.display =
                document.querySelector('input[name=mode]:checked').value === 'merge' ? 'flex' : 'none';
              syncEnumRow();
            }));

            function collectUnits() {
              const rows = [...document.querySelectorAll('#unitList .mod-item')];
              const arr = [];
              rows.forEach(r => {
                const cb = r.querySelector('input[type=checkbox]');
                if (!cb || !cb.checked) return;
                let idx = -1;
                if (kind === 'part') {
                  const t = (r.querySelector('.idx-input') || { value: '' }).value.trim();
                  if (t !== '') {
                    const n = Number(t);
                    if (Number.isNaN(n) || !Number.isInteger(n) || n < 0) { idx = -9; }
                    else idx = n;
                  }
                }
                arr.push({ name: cb.dataset.name, idx: idx });
              });
              return arr;
            }

            let currentJobId = '';

            $('btnExport').addEventListener('click', async () => {
              if (!curScad) return alert('먼저 SCAD 파일을 불러오세요.');
              const sel = collectUnits();
              if (sel.some(u => u.idx === -9)) return alert('IDX는 0 이상의 정수 또는 빈칸(전체)이어야 합니다.');
              if (!sel.length) return alert('내보낼 부재를 하나 이상 선택하세요.');
              const folder = $('outFolder').value.trim();
              if (!folder) return alert('저장 폴더를 지정하세요.');
              const scaleRaw = $('scaleInput').value.trim();
              const scaleNum = Number(scaleRaw);
              if (scaleRaw !== '' && (Number.isNaN(scaleNum) || scaleNum <= 0)) {
                return alert('크기 비율은 0보다 큰 숫자로 입력하세요 (예: 1, 0.5, 2).');
              }
              const scale = scaleRaw === '' ? '1' : String(scaleNum);
              const mode = document.querySelector('input[name=mode]:checked').value;
              const body = {
                scad: curScad,
                folder: folder,
                mode: mode,
                kind: kind,
                units: sel.map(u => u.name + ':' + u.idx).join(','),
                name: $('mergeName').value.trim(),
                enum: ($('idxEnum').checked && mode === 'split' && kind === 'part') ? '1' : '',
                scale: scale
              };
              const btn = $('btnExport');
              btn.disabled = true;
              btn.textContent = '내보내기 진행 중...';
              $('progressWrap').hidden = false;
              $('btnCancelJob').hidden = false;
              setProgress(0, 1, '작업 시작 중...');
              try {
                const j = await post('/api/export', body);
                if (!j.ok) {
                  log('내보내기 실패: ' + (j.error || ''), 'err');
                  alert(j.error || '내보내기 실패');
                  resetExportUi();
                  return;
                }
                currentJobId = j.jobId;
                const done = await pollJob(j.jobId);
                renderResults(done);
                const rs = done.results || [];
                const okC = rs.filter(r => r.ok).length;
                if (done.status === 'cancelled') {
                  log('작업 취소됨: ' + okC + '/' + rs.length + ' 완료', 'warn');
                } else {
                  log('내보내기 완료: ' + okC + '/' + rs.length + ' 성공' + (done.folder ? ' → ' + done.folder : ''),
                      (okC === rs.length && rs.length > 0) ? 'ok' : 'err');
                }
                refreshSettings();
              } catch (e) {
                log('내보내기 요청 실패: ' + e, 'err');
              }
              resetExportUi();
            });

            function resetExportUi() {
              const btn = $('btnExport');
              btn.disabled = false;
              btn.textContent = '⬇ STL 내보내기';
              $('progressWrap').hidden = true;
              $('btnCancelJob').hidden = true;
              currentJobId = '';
            }

            function setProgress(done, total, label) {
              const pct = total > 0 ? Math.round(done * 100 / total) : 0;
              $('progressBar').style.width = pct + '%';
              $('progressText').textContent = label + '  (' + done + '/' + total + ')';
            }

            async function pollJob(id) {
              let failCount = 0;
              while (true) {
                try {
                  const r = await fetch('/api/job?id=' + encodeURIComponent(id));
                  const j = await r.json();
                  failCount = 0;
                  if (!j.ok) return { status: 'error', error: j.error, results: [], folder: '' };
                  const label = j.status === 'running' ? (j.current || '진행 중')
                              : (j.status === 'cancelled' ? '취소됨' : (j.status === 'error' ? '오류: ' + j.error : '완료'));
                  setProgress(j.done, j.total, label);
                  if (j.status !== 'running') return j;
                } catch (e) {
                  failCount++;
                  if (failCount === 1) log('서버 응답 지연 — 재시도 중...', 'warn');
                  if (failCount >= 10) {
                    log('서버에 연결할 수 없습니다. 작업 상태를 확인할 수 없습니다.', 'err');
                    return { status: 'error', error: '서버 연결 끊김', results: [], folder: '' };
                  }
                }
                await new Promise(res => setTimeout(res, 800));
              }
            }

            $('btnCancelJob').addEventListener('click', async () => {
              if (!currentJobId) return;
              await post('/api/cancel', { id: currentJobId });
              log('취소 요청 — 현재 렌더 종료 후 중단됩니다', 'warn');
            });

            function renderResults(j) {
              const tb = $('resultTable').querySelector('tbody');
              tb.innerHTML = '';
              let anyOk = false;
              (j.results || []).forEach(r => {
                if (r.ok) anyOk = true;
                const tr = document.createElement('tr');
                tr.title = r.log || '';
                const cells = [
                  [r.name, ''],
                  [r.ok ? '성공' : '실패', r.ok ? 'ok' : 'err'],
                  [r.size >= 0 ? sizeFmt(r.size) : '-', ''],
                  [r.log1 || '', '']
                ];
                cells.forEach(c => {
                  const td = document.createElement('td');
                  if (c[1]) td.className = c[1];
                  td.textContent = c[0];
                  tr.appendChild(td);
                });
                const tdView = document.createElement('td');
                if (r.ok && j.folder) {
                  const b = document.createElement('button');
                  b.className = 'btn-mini';
                  b.textContent = '3D';
                  b.title = r.name + ' 3D 미리보기';
                  b.addEventListener('click', () => openViewer(joinPath(j.folder, r.name), r.name));
                  tdView.appendChild(b);
                } else {
                  tdView.textContent = '-';
                }
                tr.appendChild(tdView);
                tb.appendChild(tr);
              });
              $('btnOpenFolder').hidden = !anyOk;
              if (anyOk && j.folder) lastFolder = j.folder;
            }

            function joinPath(dir, name) {
              const BS = String.fromCharCode(92), SL = String.fromCharCode(47);
              const sep = dir.endsWith(SL) || dir.endsWith(BS) ? '' : (dir.indexOf(BS) >= 0 ? BS : SL);
              return dir + sep + name;
            }

            $('btnOpenFolder').addEventListener('click', async () => {
              if (!lastFolder) return;
              await post('/api/openfolder', { path: lastFolder });
            });

            /* ---- 폴더 브라우저 ---- */
            function showDir(j) {
              browseState = { path: j.current, parent: j.parent };
              $('browsePath').value = j.current;
              const dr = $('driveRow');
              dr.innerHTML = '';
              (j.drives || []).forEach(d => {
                const b = document.createElement('button');
                b.textContent = d;
                b.addEventListener('click', () => loadDir(d, ''));
                dr.appendChild(b);
              });
              const dl = $('dirList');
              dl.innerHTML = '';
              (j.dirs || []).forEach(d => {
                const b = document.createElement('button');
                b.className = 'dir-item';
                b.textContent = '📁 ' + d;
                b.addEventListener('click', () => loadDir(j.current, d));
                dl.appendChild(b);
              });
              if (!(j.dirs || []).length) {
                const p = document.createElement('p');
                p.className = 'hint';
                p.style.padding = '10px';
                p.textContent = '하위 폴더가 없습니다.';
                dl.appendChild(p);
              }
            }

            async function loadDir(path, child) {
              const d = { path: path };
              if (child) d.child = child;
              const j = await post('/api/browse', d);
              if (!j.ok) { log('폴더 탐색 실패: ' + j.error, 'err'); alert(j.error); return; }
              showDir(j);
            }

            async function loadSpecial(name) {
              const j = await post('/api/browse', { special: name });
              if (!j.ok) { log('폴더 이동 실패: ' + j.error, 'err'); return; }
              showDir(j);
            }

            $('btnBrowse').addEventListener('click', () => {
              $('folderModal').hidden = false;
              loadDir($('outFolder').value.trim() || '', '');
            });
            $('btnGo').addEventListener('click', () => loadDir($('browsePath').value.trim(), ''));
            $('btnUp').addEventListener('click', () => loadDir(browseState.parent || '', ''));
            document.querySelectorAll('.quick').forEach(b =>
              b.addEventListener('click', () => loadSpecial(b.dataset.quick)));
            $('btnMkdir').addEventListener('click', async () => {
              const name = $('newDirName').value.trim();
              if (!name) return alert('새 폴더 이름을 입력하세요.');
              const j = await post('/api/mkdir', { path: browseState.path, name: name });
              if (!j.ok) return alert(j.error);
              $('newDirName').value = '';
              log('폴더 생성: ' + j.current, 'ok');
              loadDir(j.current, '');
            });
            $('btnPickFolder').addEventListener('click', () => {
              $('outFolder').value = browseState.path;
              $('folderModal').hidden = true;
            });
            $('btnCloseModal').addEventListener('click', () => { $('folderModal').hidden = true; });

            /* ---- 최근 기록 ---- */
            async function refreshSettings() {
              try {
                const r = await fetch('/api/settings');
                const j = await r.json();
                renderChips($('recentScads'), j.scads || [], p => { $('scadPath').value = p; });
                renderChips($('recentFolders'), j.folders || [], p => {
                  $('outFolder').value = p;
                  if (!$('folderModal').hidden) loadDir(p, '');
                });
              } catch (e) {
                log('최근 기록 로드 실패: ' + e, 'warn');
              }
            }

            function renderChips(box, items, onPick) {
              box.innerHTML = '';
              if (!items.length) { box.hidden = true; return; }
              box.hidden = false;
              items.forEach(p => {
                const c = document.createElement('span');
                c.className = 'chip';
                c.textContent = p;
                c.title = p;
                c.addEventListener('click', () => onPick(p));
                box.appendChild(c);
              });
            }

            /* ---- 3D 미리보기 (WebGL 직접 구현, 외부 라이브러리 없음) ---- */
            let glCtx = null, glProg = null, glBufPos = null, glBufNrm = null;
            let glPosLoc = null, glNrmLoc = null;
            let triCount = 0, viewAnim = 0;
            let cam = { theta: 0.7, phi: 1.0, dist: 3.2, tx: 0, ty: 0 };

            const VS_SRC = 'attribute vec3 aPos; attribute vec3 aNrm;' +
              'uniform mat4 uMVP; uniform mat3 uRot; varying vec3 vN;' +
              'void main(){ vN = uRot * aNrm; gl_Position = uMVP * vec4(aPos, 1.0); }';
            const FS_SRC = 'precision mediump float; varying vec3 vN;' +
              'void main(){ vec3 n = normalize(vN);' +
              'float l1 = max(dot(n, normalize(vec3(0.4, 0.6, 0.8))), 0.0);' +
              'float l2 = max(dot(n, normalize(vec3(-0.5, -0.2, 0.5))), 0.0);' +
              'vec3 col = vec3(0.78, 0.82, 0.88) * (0.28 + 0.55 * l1 + 0.22 * l2);' +
              'gl_FragColor = vec4(col, 1.0); }';

            function openViewer(path, name) {
              $('viewTitle').textContent = '3D 미리보기 — ' + name;
              $('viewModal').hidden = false;
              $('viewInfo').textContent = '불러오는 중...';
              fetch('/api/preview?path=' + encodeURIComponent(path))
                .then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.arrayBuffer(); })
                .then(buf => {
                  const mesh = parseStl(buf);
                  if (!mesh || !mesh.count) {
                    $('viewInfo').textContent = 'STL 파싱 실패 (빈 파일이거나 형식을 인식하지 못했습니다)';
                    return;
                  }
                  setupMesh(mesh);
                  $('viewInfo').textContent = '삼각형 ' + mesh.count.toLocaleString() + '개 · 크기 '
                    + mesh.dims[0].toFixed(1) + ' × ' + mesh.dims[1].toFixed(1) + ' × ' + mesh.dims[2].toFixed(1) + ' mm';
                  startRender();
                })
                .catch(e => { $('viewInfo').textContent = '미리보기 실패: ' + e; });
            }

            function parseStl(buf) {
              if (buf.byteLength >= 84) {
                const dv = new DataView(buf);
                const n = dv.getUint32(80, true);
                if (n > 0 && 84 + n * 50 === buf.byteLength) return parseStlBinary(dv, n);
              }
              return parseStlAscii(buf);
            }

            function parseStlBinary(dv, n) {
              const pos = new Float32Array(n * 9);
              const nrm = new Float32Array(n * 9);
              let minX = 1e30, minY = 1e30, minZ = 1e30, maxX = -1e30, maxY = -1e30, maxZ = -1e30;
              for (let i = 0; i < n; i++) {
                const off = 84 + i * 50 + 12;
                for (let v = 0; v < 3; v++) {
                  const k = i * 9 + v * 3;
                  pos[k] = dv.getFloat32(off + v * 12, true);
                  pos[k + 1] = dv.getFloat32(off + v * 12 + 4, true);
                  pos[k + 2] = dv.getFloat32(off + v * 12 + 8, true);
                  if (pos[k] < minX) minX = pos[k]; if (pos[k] > maxX) maxX = pos[k];
                  if (pos[k + 1] < minY) minY = pos[k + 1]; if (pos[k + 1] > maxY) maxY = pos[k + 1];
                  if (pos[k + 2] < minZ) minZ = pos[k + 2]; if (pos[k + 2] > maxZ) maxZ = pos[k + 2];
                }
                faceNormal(pos, nrm, i);
              }
              return finishMesh(pos, nrm, n, minX, minY, minZ, maxX, maxY, maxZ);
            }

            function parseStlAscii(buf) {
              const text = new TextDecoder('utf-8').decode(buf).trim();
              if (text.indexOf('solid') !== 0) return null;
              const verts = [];
              const lines = text.split(String.fromCharCode(10));
              for (let i = 0; i < lines.length; i++) {
                const t = lines[i].trim();
                if (t.indexOf('vertex') !== 0) continue;
                const parts = t.split(' ').filter(s => s.length > 0);
                if (parts.length >= 4) verts.push(parseFloat(parts[1]), parseFloat(parts[2]), parseFloat(parts[3]));
              }
              const n = Math.floor(verts.length / 9);
              if (n <= 0) return null;
              const pos = new Float32Array(verts.slice(0, n * 9));
              const nrm = new Float32Array(n * 9);
              let minX = 1e30, minY = 1e30, minZ = 1e30, maxX = -1e30, maxY = -1e30, maxZ = -1e30;
              for (let k = 0; k < n * 9; k += 3) {
                if (pos[k] < minX) minX = pos[k]; if (pos[k] > maxX) maxX = pos[k];
                if (pos[k + 1] < minY) minY = pos[k + 1]; if (pos[k + 1] > maxY) maxY = pos[k + 1];
                if (pos[k + 2] < minZ) minZ = pos[k + 2]; if (pos[k + 2] > maxZ) maxZ = pos[k + 2];
              }
              for (let i = 0; i < n; i++) faceNormal(pos, nrm, i);
              return finishMesh(pos, nrm, n, minX, minY, minZ, maxX, maxY, maxZ);
            }

            function faceNormal(pos, nrm, i) {
              const ax = pos[i * 9 + 3] - pos[i * 9], ay = pos[i * 9 + 4] - pos[i * 9 + 1], az = pos[i * 9 + 5] - pos[i * 9 + 2];
              const bx = pos[i * 9 + 6] - pos[i * 9], by = pos[i * 9 + 7] - pos[i * 9 + 1], bz = pos[i * 9 + 8] - pos[i * 9 + 2];
              let nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
              const len = Math.hypot(nx, ny, nz) || 1;
              nx /= len; ny /= len; nz /= len;
              for (let v = 0; v < 3; v++) {
                nrm[i * 9 + v * 3] = nx;
                nrm[i * 9 + v * 3 + 1] = ny;
                nrm[i * 9 + v * 3 + 2] = nz;
              }
            }

            function finishMesh(pos, nrm, n, minX, minY, minZ, maxX, maxY, maxZ) {
              const dims = [Math.max(0, maxX - minX), Math.max(0, maxY - minY), Math.max(0, maxZ - minZ)];
              return {
                pos: pos, nrm: nrm, count: n, dims: dims,
                center: [(minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2],
                radius: Math.max(dims[0], dims[1], dims[2]) || 1
              };
            }

            function setupMesh(mesh) {
              const c = mesh.center, s = 2 / mesh.radius;
              for (let k = 0; k < mesh.pos.length; k += 3) {
                mesh.pos[k] = (mesh.pos[k] - c[0]) * s;
                mesh.pos[k + 1] = (mesh.pos[k + 1] - c[1]) * s;
                mesh.pos[k + 2] = (mesh.pos[k + 2] - c[2]) * s;
              }
              const canvas = $('viewCanvas');
              if (!glCtx) initGl(canvas);
              glCtx.bindBuffer(glCtx.ARRAY_BUFFER, glBufPos);
              glCtx.bufferData(glCtx.ARRAY_BUFFER, mesh.pos, glCtx.STATIC_DRAW);
              glCtx.bindBuffer(glCtx.ARRAY_BUFFER, glBufNrm);
              glCtx.bufferData(glCtx.ARRAY_BUFFER, mesh.nrm, glCtx.STATIC_DRAW);
              triCount = mesh.count;
              cam = { theta: 0.7, phi: 1.0, dist: 3.2, tx: 0, ty: 0 };
            }

            function initGl(canvas) {
              glCtx = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
              if (!glCtx) { $('viewInfo').textContent = '이 브라우저는 WebGL을 지원하지 않습니다.'; return; }
              const mk = (type, src) => {
                const sh = glCtx.createShader(type);
                glCtx.shaderSource(sh, src);
                glCtx.compileShader(sh);
                return sh;
              };
              glProg = glCtx.createProgram();
              glCtx.attachShader(glProg, mk(glCtx.VERTEX_SHADER, VS_SRC));
              glCtx.attachShader(glProg, mk(glCtx.FRAGMENT_SHADER, FS_SRC));
              glCtx.linkProgram(glProg);
              glCtx.useProgram(glProg);
              glBufPos = glCtx.createBuffer();
              glBufNrm = glCtx.createBuffer();
              glPosLoc = glCtx.getAttribLocation(glProg, 'aPos');
              glNrmLoc = glCtx.getAttribLocation(glProg, 'aNrm');
              glCtx.enable(glCtx.DEPTH_TEST);
              bindAttribs();
              initControls(canvas);
            }

            function bindAttribs() {
              glCtx.bindBuffer(glCtx.ARRAY_BUFFER, glBufPos);
              glCtx.vertexAttribPointer(glPosLoc, 3, glCtx.FLOAT, false, 0, 0);
              glCtx.enableVertexAttribArray(glPosLoc);
              glCtx.bindBuffer(glCtx.ARRAY_BUFFER, glBufNrm);
              glCtx.vertexAttribPointer(glNrmLoc, 3, glCtx.FLOAT, false, 0, 0);
              glCtx.enableVertexAttribArray(glNrmLoc);
            }

            function startRender() {
              cancelAnimationFrame(viewAnim);
              const canvas = $('viewCanvas');
              const frame = () => {
                if ($('viewModal').hidden) return;
                const dpr = window.devicePixelRatio || 1;
                const w = Math.max(1, Math.round(canvas.clientWidth * dpr));
                const h = Math.max(1, Math.round(canvas.clientHeight * dpr));
                if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
                glCtx.viewport(0, 0, canvas.width, canvas.height);
                glCtx.clearColor(0.08, 0.10, 0.15, 1);
                glCtx.clear(glCtx.COLOR_BUFFER_BIT | glCtx.DEPTH_BUFFER_BIT);
                const proj = matPerspective(45 * Math.PI / 180, canvas.width / canvas.height, 0.05, 100);
                const view = matOrbit(cam.theta, cam.phi, cam.dist, cam.tx, cam.ty);
                glCtx.uniformMatrix4fv(glCtx.getUniformLocation(glProg, 'uMVP'), false, matMul(proj, view));
                glCtx.uniformMatrix3fv(glCtx.getUniformLocation(glProg, 'uRot'), false, mat3FromView(view));
                bindAttribs();
                glCtx.drawArrays(glCtx.TRIANGLES, 0, triCount * 3);
                viewAnim = requestAnimationFrame(frame);
              };
              viewAnim = requestAnimationFrame(frame);
            }

            function matPerspective(fovy, aspect, near, far) {
              const f = 1 / Math.tan(fovy / 2), nf = 1 / (near - far);
              return new Float32Array([f / aspect, 0, 0, 0, 0, f, 0, 0, 0, 0, (far + near) * nf, -1, 0, 0, 2 * far * near * nf, 0]);
            }

            function matOrbit(theta, phi, dist, tx, ty) {
              const ex = dist * Math.cos(phi) * Math.sin(theta) + tx;
              const ey = dist * Math.cos(phi) * Math.cos(theta) + ty;
              const ez = dist * Math.sin(phi);
              return lookAt([ex, ey, ez], [tx, ty, 0], [0, 0, 1]);
            }

            function lookAt(eye, center, up) {
              let zx = eye[0] - center[0], zy = eye[1] - center[1], zz = eye[2] - center[2];
              let l = Math.hypot(zx, zy, zz) || 1;
              zx /= l; zy /= l; zz /= l;
              let xx = up[1] * zz - up[2] * zy, xy = up[2] * zx - up[0] * zz, xz = up[0] * zy - up[1] * zx;
              l = Math.hypot(xx, xy, xz) || 1;
              xx /= l; xy /= l; xz /= l;
              const yx = zy * xz - zz * xy, yy = zz * xx - zx * xz, yz = zx * xy - zy * xx;
              return new Float32Array([
                xx, yx, zx, 0,
                xy, yy, zy, 0,
                xz, yz, zz, 0,
                -(xx * eye[0] + xy * eye[1] + xz * eye[2]),
                -(yx * eye[0] + yy * eye[1] + yz * eye[2]),
                -(zx * eye[0] + zy * eye[1] + zz * eye[2]),
                1
              ]);
            }

            function matMul(a, b) {
              const o = new Float32Array(16);
              for (let c = 0; c < 4; c++) {
                for (let r = 0; r < 4; r++) {
                  o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
                }
              }
              return o;
            }

            function mat3FromView(v) {
              return new Float32Array([v[0], v[1], v[2], v[4], v[5], v[6], v[8], v[9], v[10]]);
            }

            function initControls(canvas) {
              let dragging = 0, lx = 0, ly = 0;
              canvas.addEventListener('contextmenu', e => e.preventDefault());
              canvas.addEventListener('mousedown', e => {
                dragging = (e.shiftKey || e.button === 2) ? 2 : 1;
                lx = e.clientX; ly = e.clientY;
              });
              window.addEventListener('mouseup', () => { dragging = 0; });
              canvas.addEventListener('mousemove', e => {
                if (!dragging) return;
                const dx = e.clientX - lx, dy = e.clientY - ly;
                lx = e.clientX; ly = e.clientY;
                if (dragging === 1) {
                  cam.theta -= dx * 0.01;
                  cam.phi = Math.min(1.55, Math.max(-1.55, cam.phi + dy * 0.01));
                } else {
                  const k = 0.002 * cam.dist;
                  const rvx = Math.sin(cam.theta + Math.PI / 2), rvy = Math.cos(cam.theta + Math.PI / 2);
                  cam.tx -= dx * k * rvx;
                  cam.ty -= dx * k * rvy;
                  const uvx = Math.sin(cam.phi) * Math.cos(cam.theta), uvy = Math.sin(cam.phi) * Math.sin(cam.theta);
                  cam.tx += dy * k * uvx;
                  cam.ty += dy * k * uvy;
                }
              });
              canvas.addEventListener('wheel', e => {
                e.preventDefault();
                cam.dist *= (e.deltaY > 0 ? 1.1 : 0.9);
                cam.dist = Math.min(20, Math.max(0.5, cam.dist));
              }, { passive: false });
            }

            $('btnViewClose').addEventListener('click', () => {
              $('viewModal').hidden = true;
              cancelAnimationFrame(viewAnim);
            });

            initEnv();
            refreshSettings();
            </script>
            </body>
            </html>
            """;
}

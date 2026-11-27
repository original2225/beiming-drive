package games.beiming.drive.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 孤儿 blob 清扫的行为测试。
 *
 * <p>这个类会删磁盘上的文件，所以断言的重点不是「删得对不对」，
 * 而是「<b>不该删的绝不删</b>」。每条测试都对应一条安全边界：
 * 有人引用的、刚写进来的、形状不像 blob 的，一律不许动。
 */
class BlobGcServiceTest {

    @TempDir
    Path root;

    private BlobStore blobs;
    private JdbcTemplate jdbc;
    private BlobGcService gc;

    private static final String SHA_A = "aa".repeat(32);
    private static final String SHA_B = "bb".repeat(32);
    private static final String SHA_C = "cc".repeat(32);

    @BeforeEach
    void setUp() throws IOException {
        blobs = new BlobStore(root.toString());
        blobs.init();
        jdbc = mock(JdbcTemplate.class);
        // 默认：没有任何引用
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        gc = new BlobGcService(jdbc, blobs, 0);   // 冷静期 0，方便测「时间」这条边界
    }

    /** 在 root/ab/cd/ 下造一个 blob，并把修改时间推远，免得踩冷静期 */
    private Path makeBlob(String sha, byte[] content, boolean old) throws IOException {
        Path p = blobs.pathOf(sha);
        Files.createDirectories(p.getParent());
        Files.write(p, content);
        if (old) {
            Files.setLastModifiedTime(p,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L));
        }
        return p;
    }

    private void referencing(String... shas) {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of(shas));
    }

    @Test
    @DisplayName("没人引用的 blob 会被清掉，并报告释放的字节数")
    void deletesOrphan() throws IOException {
        Path p = makeBlob(SHA_A, new byte[100], true);

        BlobGcService.GcReport r = gc.collect(false);

        assertFalse(Files.exists(p), "无引用的 blob 应该被删掉");
        assertEquals(1, r.deleted());
        assertEquals(100, r.reclaimedBytes());
        assertEquals(1, r.scanned());
    }

    @Test
    @DisplayName("还有节点引用的 blob 必须留下（去重的意义就在这）")
    void keepsReferenced() throws IOException {
        Path p = makeBlob(SHA_A, new byte[50], true);
        referencing(SHA_A);

        BlobGcService.GcReport r = gc.collect(false);

        assertTrue(Files.exists(p), "只要还有一个节点引用，实体就不能删");
        assertEquals(0, r.deleted());
        assertEquals(1, r.referenced());
    }

    @Test
    @DisplayName("刚写进来、还没入库的 blob 不能被当成孤儿")
    void keepsTooFresh() throws IOException {
        // 这是上传中的真实形态：BlobStore.put 已经把文件落盘，节点还没 insert
        Path p = makeBlob(SHA_A, new byte[10], false);
        BlobGcService guarded = new BlobGcService(jdbc, blobs, 30);

        BlobGcService.GcReport r = guarded.collect(false);

        assertTrue(Files.exists(p), "冷静期内的新文件不能删，否则正在上传的内容会被抹掉");
        assertEquals(1, r.skippedRecent());
        assertEquals(0, r.deleted());
    }

    @Test
    @DisplayName("形状不像 sha256 的文件一律不碰（上传临时文件就在这个目录里）")
    void ignoresNonBlobShapes() throws IOException {
        Path tmp = root.resolve("up-123456.part");
        Files.write(tmp, new byte[8]);
        Files.setLastModifiedTime(tmp,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L));
        Path nested = root.resolve("backup").resolve("notes.txt");
        Files.createDirectories(nested.getParent());
        Files.write(nested, new byte[8]);

        BlobGcService.GcReport r = gc.collect(false);

        assertTrue(Files.exists(tmp), "根目录的 up-*.part 是上传临时文件，清扫任务不许删");
        assertTrue(Files.exists(nested), "不认识的目录结构不许碰");
        assertEquals(0, r.scanned(), "形状不对的不算进扫描计数");
    }

    @Test
    @DisplayName("dryRun 只统计不落刀")
    void dryRunTouchesNothing() throws IOException {
        Path p = makeBlob(SHA_A, new byte[64], true);

        BlobGcService.GcReport r = gc.collect(true);

        assertTrue(Files.exists(p), "预演不能删任何东西");
        assertEquals(1, r.deleted(), "但统计要如实说「会删 1 个」");
        assertEquals(64, r.reclaimedBytes());
        assertTrue(r.dryRun());
    }

    @Test
    @DisplayName("releaseIfOrphan：删完记录后精确释放，只动传进来的那几个")
    void releaseOnlyGivenShas() throws IOException {
        Path a = makeBlob(SHA_A, new byte[20], true);
        Path b = makeBlob(SHA_B, new byte[20], true);

        BlobGcService.Release rel = gc.releaseIfOrphan(Set.of(SHA_A));

        assertFalse(Files.exists(a), "明确点名释放的应该删掉");
        assertTrue(Files.exists(b), "没点名的绝不能被顺手删掉");
        assertEquals(1, rel.count());
        assertEquals(20, rel.bytes());
    }

    @Test
    @DisplayName("releaseIfOrphan：库里还有引用就不删（同一份内容在别处还留着）")
    void releaseKeepsStillReferenced() throws IOException {
        Path a = makeBlob(SHA_A, new byte[20], true);
        when(jdbc.queryForObject(anyString(), eq(Integer.class),
                eq(SHA_A), eq(SHA_A))).thenReturn(2);

        BlobGcService.Release rel = gc.releaseIfOrphan(List.of(SHA_A));

        assertTrue(Files.exists(a), "别的节点或头像还在用，不能删");
        assertEquals(0, rel.count());
    }

    @Test
    @DisplayName("reclaim 出来的字节数要和文件真实大小一致")
    void reclaimedBytesAreReal() throws IOException {
        makeBlob(SHA_A, new byte[1234], true);
        makeBlob(SHA_C, new byte[766], true);

        BlobGcService.GcReport r = gc.collect(false);

        assertEquals(2000, r.reclaimedBytes(), "释放字节数应该是 1234+766");
        assertEquals(2, r.deleted());
    }
}
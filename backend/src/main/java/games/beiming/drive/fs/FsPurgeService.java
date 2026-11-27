package games.beiming.drive.fs;

import games.beiming.drive.storage.BlobGcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 彻底删除的编排层：**先删记录（事务），再删文件（事务外）**。
 *
 * <p>为什么必须分成两步、写在外面：
 * <ul>
 *   <li>如果把 {@code Files.delete} 放进 {@code @Transactional} 里，事务一旦回滚，
 *       数据库记录还在、磁盘文件已经没了 —— 用户点开是个 404，比没删更糟。
 *       文件系统没有回滚。</li>
 *   <li>反过来，先删文件再删记录，删文件成功了但事务失败，记录指向不存在的实体。</li>
 * </ul>
 * 所以只能「记录删掉并提交」→「释放实体」。释放失败也不影响正确性：
 * 记录已经没了，孤儿 blob 留给定时清扫收尾，最多多占一会儿磁盘。
 *
 * <p>另一个存在理由：用户手点的「彻底删除」和定时任务的「过了 30 天清了」必须是同一套逻辑。
 * 两处各写一遍，早晚有一边忘了清索引或者忘了放 blob。
 */
@Service
public class FsPurgeService {

    private static final Logger log = LoggerFactory.getLogger(FsPurgeService.class);

    private final FileService files;
    private final BlobGcService gc;

    public FsPurgeService(FileService files, BlobGcService gc) {
        this.files = files;
        this.gc = gc;
    }

    public record Result(int purgedEntries, int blobsReleased, long freedBytes) {
    }

    /** 彻底删除一条（目录则连带整棵子树）。 */
    public Result purgeOne(long ownerId, long nodeId) {
        List<String> shas = files.purge(ownerId, nodeId);
        BlobGcService.Release rel = gc.releaseIfOrphan(shas);
        return new Result(1, rel.count(), rel.bytes());
    }

    /** 清空一个用户的整个回收站（只删顶层条目，子树跟着走）。 */
    public Result purgeAllTrashed(long ownerId) {
        List<FileService.Node> tops = files.trashList(ownerId);
        List<String> allShas = new ArrayList<>();
        int count = 0;
        for (FileService.Node n : tops) {
            try {
                allShas.addAll(files.purge(ownerId, n.id()));
                count++;
            } catch (RuntimeException e) {
                // 一条失败不该拖垮整轮清理；下一轮再收
                log.warn("清空回收站时删除节点 {} 失败: {}", n.id(), e.toString());
            }
        }
        BlobGcService.Release rel = gc.releaseIfOrphan(allShas);
        return new Result(count, rel.count(), rel.bytes());
    }
}
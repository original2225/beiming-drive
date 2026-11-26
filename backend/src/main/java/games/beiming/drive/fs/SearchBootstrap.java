package games.beiming.drive.fs;

import games.beiming.drive.search.SearchIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 启动时把库里已有的节点灌进索引。
 *
 * <p>为什么需要：索引是独立于数据库的存储，第一次部署时它是空的；
 * 而且换版本、索引目录丢失后也需要能自愈。数据量小（个人网盘几万个节点），
 * 全量重建只在启动跑一次，代价很低。
 *
 * <p>放到 {@link ApplicationRunner} 里做，而不是 {@code @PostConstruct}，
 * 是为了保证 JdbcTemplate 和 Flyway 迁移都已经就绪。
 */
@Configuration
public class SearchBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SearchBootstrap.class);

    @Bean
    public ApplicationRunner rebuildSearchIndexOnStartup(FileService files,
                                                         FsSearchSync sync,
                                                         SearchIndex index,
                                                         JdbcTemplate jdbc) {
        return (ApplicationArguments args) -> {
            try {
                // 库里应该有、索引里应该有的全部节点（回收站不算）
                List<FileService.Node> live = jdbc.query(
                        "select * from drv_node where status <> 'TRASHED' and parent_id is not null",
                        (rs, i) -> new FileService.Node(
                                rs.getLong("id"), rs.getLong("owner_id"),
                                (Long) rs.getObject("parent_id"),
                                rs.getString("name"), rs.getString("path"), rs.getString("kind"),
                                rs.getLong("size"), rs.getString("mime"), rs.getString("sha256"),
                                rs.getString("status"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("updated_at").toInstant()));

                int before = index.docCount();
                if (before == live.size()) {
                    log.info("搜索索引已对齐，跳过重建（{} 条）", before);
                    return;
                }
                sync.rebuildAll(live);
                log.info("搜索索引启动对齐：{} → {} 条", before, index.docCount());
            } catch (Exception e) {
                // 索引失败不能拦住应用启动：搜索不可用比整个网盘起不来强
                log.error("搜索索引启动重建失败，搜索将暂时不可用：{}", e.toString());
            }
        };
    }
}
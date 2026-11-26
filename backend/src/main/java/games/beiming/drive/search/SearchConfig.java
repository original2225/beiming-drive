package games.beiming.drive.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 搜索引擎装配。索引目录可配置（容器里指到卷上），默认落在工作目录下。
 *
 * <p>注意：这是**唯一**一处需要按项目改的地方（路径配置），
 * search 包本身不含任何 Spring 注解，复制到别的项目时不用带着这段。
 */
@Configuration
public class SearchConfig {

    private static final Logger log = LoggerFactory.getLogger(SearchConfig.class);

    @Bean(destroyMethod = "close")
    public SearchIndex searchIndex(@Value("${drive.search.index-dir:data/search-index}") String dir) {
        try {
            Path p = Paths.get(dir).toAbsolutePath();
            SearchIndex idx = new SearchIndex(p);
            log.info("搜索索引就绪，目录={} 现有文档={}", p, idx.docCount());
            return idx;
        } catch (IOException e) {
            throw new IllegalStateException("搜索索引初始化失败: " + dir, e);
        }
    }
}
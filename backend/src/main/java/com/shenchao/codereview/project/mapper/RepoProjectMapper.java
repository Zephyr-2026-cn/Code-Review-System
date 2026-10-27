package com.shenchao.codereview.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shenchao.codereview.project.entity.RepoProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RepoProjectMapper extends BaseMapper<RepoProject> {

    @Select("SELECT count(1) FROM repo_project WHERE code = #{code} AND deleted = 0")
    long countByCode(@Param("code") String code);

    @Select("""
            SELECT count(1) FROM repo_project
            WHERE repo_url = #{repoUrl} AND deleted = 0
            """)
    long countByRepoUrl(@Param("repoUrl") String repoUrl);

    /**
     * 原子地把状态置为 CLONING，返回受影响行数。
     *
     * <p>用 {@code WHERE sync_status <> 'CLONING'} 做乐观抢占：
     * 两个同步请求同时到达时只有一个能拿到 1，另一个直接放弃，
     * 避免同一目录被两个线程并发写入（JGit 会抛 LockFailedException）。
     *
     * <p>同时带 30 分钟的"僵尸状态"接管条件：进程在克隆途中被杀掉时，
     * 状态会永久停在 CLONING，没有这个兜底该项目将再也无法同步。
     */
    @org.apache.ibatis.annotations.Update("""
            UPDATE repo_project
            SET sync_status = 'CLONING', updated_at = now()
            WHERE id = #{id} AND deleted = 0
              AND (sync_status <> 'CLONING' OR updated_at < now() - interval '30 minutes')
            """)
    int tryMarkCloning(@Param("id") Long id);

    /** 同步成功后落库，同时清空上次错误信息。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE repo_project
            SET sync_status = 'READY',
                last_synced_at = now(),
                last_commit_id = #{commitId},
                last_commit_message = #{commitMessage},
                last_commit_at = #{commitAt},
                last_error = NULL,
                updated_at = now()
            WHERE id = #{id} AND deleted = 0
            """)
    int markReady(@Param("id") Long id,
                  @Param("commitId") String commitId,
                  @Param("commitMessage") String commitMessage,
                  @Param("commitAt") java.time.OffsetDateTime commitAt);

    @org.apache.ibatis.annotations.Update("""
            UPDATE repo_project
            SET sync_status = 'FAILED', last_error = #{error}, updated_at = now()
            WHERE id = #{id} AND deleted = 0
            """)
    int markFailed(@Param("id") Long id, @Param("error") String error);
}

package com.shenchao.codereview.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shenchao.codereview.task.entity.ReviewTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 审查任务数据访问。
 *
 * <p><b>所有状态变更都用带 {@code AND status = ?} 的条件更新</b>，靠数据库行锁保证原子性：
 * 队列可能重复投递、恢复任务可能与 worker 同时动作，只有条件更新能保证"同一任务只有一个执行者"。
 */
public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    /** 状态机迁移：仅当当前状态等于 {@code from} 时才更新，返回 0 表示已被别人抢走。 */
    @Update("""
            UPDATE review_task
            SET status = #{to}, updated_at = now(), started_at = COALESCE(started_at, now())
            WHERE id = #{id} AND status = #{from}
            """)
    int compareAndSetStatus(@Param("id") Long id, @Param("from") String from, @Param("to") String to);

    /** 更新进度快照（同时充当心跳，供僵尸任务巡检判断）。 */
    @Update("""
            UPDATE review_task
            SET stats_json = #{statsJson}, updated_at = now()
            WHERE id = #{id}
            """)
    int updateProgress(@Param("id") Long id, @Param("statsJson") String statsJson);

    /** 写入终态：状态、失败阶段、失败原因、缺陷数、统计与完成时间一次落库。 */
    @Update("""
            UPDATE review_task
            SET status = #{status},
                fail_stage = #{failStage},
                fail_reason = #{failReason},
                issue_count = #{issueCount},
                stats_json = #{statsJson},
                finished_at = now(),
                updated_at = now()
            WHERE id = #{id}
            """)
    int finish(@Param("id") Long id,
               @Param("status") String status,
               @Param("failStage") String failStage,
               @Param("failReason") String failReason,
               @Param("issueCount") int issueCount,
               @Param("statsJson") String statsJson);

    /** 请求取消：仅在途任务可置位，终态任务返回 0。 */
    @Update("""
            UPDATE review_task
            SET cancel_requested = TRUE, updated_at = now()
            WHERE id = #{id}
              AND cancel_requested = FALSE
              AND status IN ('CREATED', 'QUEUED', 'FETCHING', 'SLICING', 'ANALYZING', 'FUSING')
            """)
    int requestCancel(@Param("id") Long id);

    @Select("SELECT cancel_requested FROM review_task WHERE id = #{id}")
    Boolean selectCancelRequested(@Param("id") Long id);

    /** 在途但长时间没有心跳的任务（进程被杀、容器重启留下的残局）。 */
    @Select("""
            SELECT * FROM review_task
            WHERE status IN ('FETCHING', 'SLICING', 'ANALYZING', 'FUSING')
              AND updated_at < #{before}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<ReviewTask> selectStaleActive(@Param("before") OffsetDateTime before, @Param("limit") int limit);

    /**
     * 待重新投递的任务：停留在 CREATED（没入队成功）或 QUEUED（消息丢失）超过阈值。
     *
     * <p>用 {@code updated_at} 而不是 {@code created_at} 做判断：
     * 任务从 CREATED 迁到 QUEUED 时会刷新 {@code updated_at}，
     * 这样"排队时间"从进入队列那一刻开始算，不会因为创建久远被反复重投。
     */
    @Select("""
            SELECT * FROM review_task
            WHERE status IN ('CREATED', 'QUEUED')
              AND updated_at < #{before}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<ReviewTask> selectRequeueCandidates(@Param("before") OffsetDateTime before, @Param("limit") int limit);
}

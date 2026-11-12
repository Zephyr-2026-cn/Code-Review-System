package com.shenchao.codereview.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shenchao.codereview.task.entity.ReviewTaskFile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 任务文件清单数据访问。 */
public interface ReviewTaskFileMapper extends BaseMapper<ReviewTaskFile> {

    @Insert("""
            <script>
            INSERT INTO review_task_file (task_id, file_path, change_type, language, additions, deletions,
                                          analyzed, skip_reason, defect_count, created_at)
            VALUES
            <foreach collection="files" item="it" separator=",">
                (#{it.taskId}, #{it.filePath}, #{it.changeType}, #{it.language}, #{it.additions}, #{it.deletions},
                 #{it.analyzed}, #{it.skipReason}, #{it.defectCount}, now())
            </foreach>
            </script>
            """)
    int insertBatch(@Param("files") List<ReviewTaskFile> files);

    /** 更新单个文件的分析结果（缺陷数），失败不影响主流程。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE review_task_file SET analyzed = TRUE, defect_count = #{defectCount}
            WHERE task_id = #{taskId} AND file_path = #{filePath}
            """)
    int markAnalyzed(@Param("taskId") Long taskId,
                     @Param("filePath") String filePath,
                     @Param("defectCount") int defectCount);
}

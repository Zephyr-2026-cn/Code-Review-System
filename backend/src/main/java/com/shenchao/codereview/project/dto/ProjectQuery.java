package com.shenchao.codereview.project.dto;

import com.shenchao.codereview.common.api.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "项目查询条件")
public class ProjectQuery extends PageQuery {

    @Schema(description = "关键字：名称/编码/仓库地址模糊匹配")
    @Size(max = 128, message = "长度不能超过 128")
    private String keyword;

    @Schema(description = "同步状态：UNBOUND / CLONING / READY / FAILED")
    @Pattern(regexp = "^(UNBOUND|CLONING|READY|FAILED)$", message = "取值不合法")
    private String syncStatus;

    @Schema(description = "只看我接入的项目")
    private Boolean mineOnly;

    public String getKeyword() {
        return keyword == null ? null : keyword.trim();
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }

    public Boolean getMineOnly() {
        return mineOnly;
    }

    public void setMineOnly(Boolean mineOnly) {
        this.mineOnly = mineOnly;
    }
}

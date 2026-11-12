package com.shenchao.codereview.task.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 状态机迁移表测试：这里是"任务不会进入脏状态"的最后一道防线。 */
class TaskStatusTest {

    @Test
    @DisplayName("正常路径上的迁移都被允许")
    void allowsHappyPath() {
        assertThat(TaskStatus.CREATED.canTransitTo(TaskStatus.QUEUED)).isTrue();
        assertThat(TaskStatus.QUEUED.canTransitTo(TaskStatus.FETCHING)).isTrue();
        assertThat(TaskStatus.FETCHING.canTransitTo(TaskStatus.SLICING)).isTrue();
        assertThat(TaskStatus.SLICING.canTransitTo(TaskStatus.ANALYZING)).isTrue();
        assertThat(TaskStatus.ANALYZING.canTransitTo(TaskStatus.FUSING)).isTrue();
        assertThat(TaskStatus.FUSING.canTransitTo(TaskStatus.DONE)).isTrue();
        assertThat(TaskStatus.FUSING.canTransitTo(TaskStatus.PARTIAL)).isTrue();
    }

    @Test
    @DisplayName("禁止跳阶段与回退")
    void rejectsIllegalTransitions() {
        assertThat(TaskStatus.CREATED.canTransitTo(TaskStatus.ANALYZING)).isFalse();
        assertThat(TaskStatus.ANALYZING.canTransitTo(TaskStatus.FETCHING)).isFalse();
        assertThat(TaskStatus.DONE.canTransitTo(TaskStatus.QUEUED)).isFalse();
        assertThat(TaskStatus.FAILED.canTransitTo(TaskStatus.FAILED)).isFalse();
        assertThat(TaskStatus.CANCELED.canTransitTo(TaskStatus.DONE)).isFalse();
    }

    @Test
    @DisplayName("任意在途阶段都可以失败或取消")
    void allowsFailureFromEveryActiveStage() {
        for (TaskStatus status : TaskStatus.values()) {
            if (status.active()) {
                assertThat(status.canTransitTo(TaskStatus.FAILED)).as(status + " 应允许失败").isTrue();
                assertThat(status.canTransitTo(TaskStatus.CANCELED)).as(status + " 应允许取消").isTrue();
            }
        }
    }

    @Test
    @DisplayName("终态判定与进度百分比")
    void reportsTerminalAndProgress() {
        assertThat(TaskStatus.DONE.terminal()).isTrue();
        assertThat(TaskStatus.PARTIAL.terminal()).isTrue();
        assertThat(TaskStatus.FAILED.terminal()).isTrue();
        assertThat(TaskStatus.CANCELED.terminal()).isTrue();
        assertThat(TaskStatus.ANALYZING.terminal()).isFalse();

        assertThat(TaskStatus.ANALYZING.progress()).isGreaterThan(TaskStatus.SLICING.progress());
        assertThat(TaskStatus.DONE.progress()).isEqualTo(100);
    }

    @Test
    @DisplayName("在途状态列表用于 SQL 的 IN 条件")
    void exposesActiveNames() {
        assertThat(TaskStatus.activeNames())
                .containsExactlyInAnyOrder("CREATED", "QUEUED", "FETCHING", "SLICING", "ANALYZING", "FUSING");
    }

    @Test
    @DisplayName("未知状态码回退为 CREATED，避免脏数据导致 NPE")
    void parsesSafely() {
        assertThat(TaskStatus.fromCode("done")).isEqualTo(TaskStatus.DONE);
        assertThat(TaskStatus.fromCode(null)).isEqualTo(TaskStatus.CREATED);
        assertThat(TaskStatus.fromCode("WHAT")).isEqualTo(TaskStatus.CREATED);
    }
}

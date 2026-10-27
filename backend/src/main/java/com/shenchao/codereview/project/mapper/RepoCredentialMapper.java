package com.shenchao.codereview.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shenchao.codereview.project.entity.RepoCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RepoCredentialMapper extends BaseMapper<RepoCredential> {

    @Select("SELECT count(1) FROM repo_credential WHERE name = #{name} AND deleted = 0")
    long countByName(@Param("name") String name);

    /** 统计凭据被多少项目引用，用于"仍在使用则禁止删除"。 */
    @Select("""
            SELECT count(1) FROM repo_project
            WHERE credential_id = #{credentialId} AND deleted = 0
            """)
    long countProjectsByCredentialId(@Param("credentialId") Long credentialId);
}

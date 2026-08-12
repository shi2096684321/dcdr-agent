package com.dcdr.agent.repository;

import com.dcdr.agent.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {

    Optional<UserEntity> findByUsername(String username);

    boolean existsByEmail(String email);

    /** V2.2 管理后台: 按状态统计用户数 */
    long countByStatus(String status);

    /** V2.2 管理后台: 按角色统计用户数 */
    long countByRole(String role);
}

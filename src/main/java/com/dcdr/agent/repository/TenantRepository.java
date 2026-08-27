package com.dcdr.agent.repository;

import com.dcdr.agent.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TenantRepository extends JpaRepository<TenantEntity, String> {

    /** 按名称查找 (创建时查重) */
    List<TenantEntity> findByName(String name);
}

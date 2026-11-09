package com.servicedesk.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.servicedesk.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页插件集成测试，验证 MyBatis-Plus 是否真的下发了 LIMIT 与 COUNT。
 *
 * <p>
 * 属于持久化层测试。判断依据是「当前页条数」而不是「返回总数」：插件未生效时
 * selectPage 会把全表数据一次性返回，页大小断言会直接失败，因此能可靠发现插件失效。
 * 测试使用 {@code @Transactional}，结束后回滚，不污染数据库。
 * </p>
 */
@SpringBootTest
@Transactional
class SysUserMapperPageTest {

  /** 被测 Mapper，同时用于造数据。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /**
   * 插入 3 条临时用户后，验证第一页 2 条、第二页剩余条数，且 total 为表内总数。
   */
  @Test
  @DisplayName("分页查询：total 正确且每页条数受 LIMIT 约束")
  void shouldPageUsers() {
    long existing = sysUserMapper.selectCount(null);
    for (int i = 0; i < 3; i++) {
      sysUserMapper.insert(buildUser(i));
    }

    IPage<SysUser> firstPage = sysUserMapper.selectPage(new Page<>(1, 2),
        Wrappers.<SysUser>lambdaQuery().orderByAsc(SysUser::getId));

    assertThat(firstPage.getTotal()).isEqualTo(existing + 3);
    assertThat(firstPage.getRecords()).hasSize(2);

    IPage<SysUser> secondPage = sysUserMapper.selectPage(new Page<>(2, 2),
        Wrappers.<SysUser>lambdaQuery().orderByAsc(SysUser::getId));

    long expectedSecondPageSize = Math.min(2, Math.max(0, existing + 3 - 2));
    assertThat(secondPage.getRecords()).hasSize((int) expectedSecondPageSize);
  }

  /**
   * 构造一条可插入的临时用户，用户名带纳秒后缀避免与既有数据冲突。
   *
   * @param index 序号，用于区分同一次循环内的记录
   * @return 仅填充必填字段的用户实体
   */
  private SysUser buildUser(int index) {
    SysUser user = new SysUser();
    user.setUsername("page_test_" + System.nanoTime() + "_" + index);
    user.setPasswordHash("$2a$10$0123456789012345678901234567890123456789012345678901");
    user.setDisplayName("分页测试" + index);
    user.setRole("REPORTER");
    return user;
  }
}

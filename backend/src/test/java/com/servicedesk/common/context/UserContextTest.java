package com.servicedesk.common.context;

import com.servicedesk.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录用户上下文的单元测试。
 *
 * <p>
 * 属于公共上下文层测试。重点验证两件事：清理后确实取不到用户，
 * 以及 ThreadLocal 不会跨线程串号（线程复用时最危险的缺陷）。
 * </p>
 */
class UserContextTest {

  /**
   * 每个用例结束后清理，避免污染其他用例所在的主线程。
   */
  @AfterEach
  void tearDown() {
    UserContext.clear();
  }

  /**
   * 写入后能读到同一个用户，且能取到用户 ID。
   */
  @Test
  @DisplayName("写入后可读取：get/getRequired/currentUserId 一致")
  void shouldHoldCurrentUser() {
    LoginUser user = new LoginUser(2L, "zhanggong", "张工", "ENGINEER", 1L);

    UserContext.set(user);

    assertThat(UserContext.get()).isEqualTo(user);
    assertThat(UserContext.getRequired()).isEqualTo(user);
    assertThat(UserContext.getUserId()).isEqualTo(2L);
  }

  /**
   * 清理后 get 返回 null，getRequired 抛 401。
   */
  @Test
  @DisplayName("清理后：get 为空且 getRequired 抛 401")
  void shouldClearContext() {
    UserContext.set(new LoginUser(2L, "zhanggong", "张工", "ENGINEER", 1L));

    UserContext.clear();

    assertThat(UserContext.get()).isNull();
    assertThatThrownBy(UserContext::getRequired)
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(401);
  }

  /**
   * 子线程不应看到主线程写入的身份，验证 ThreadLocal 的线程隔离。
   */
  @Test
  @DisplayName("线程隔离：子线程读不到主线程的身份")
  void shouldIsolateBetweenThreads() throws Exception {
    UserContext.set(new LoginUser(2L, "zhanggong", "张工", "ENGINEER", 1L));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<LoginUser> future = executor.submit(UserContext::get);

      assertThat(future.get()).isNull();
    } finally {
      executor.shutdownNow();
    }
  }
}

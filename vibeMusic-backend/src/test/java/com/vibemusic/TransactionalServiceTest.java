package com.vibemusic;

import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务性 Service 测试基类
 * <p>
 * data-test.sql 在每次测试方法前插入数据，@Transactional 自动回滚，
 * @DirtiesContext(AFTER_CLASS) 继承自 BaseTest，确保各类上下文隔离
 */
@Transactional
@Sql(scripts = "/data-test.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class TransactionalServiceTest extends BaseTest {
}

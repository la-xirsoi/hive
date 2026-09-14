package hive

import hive.adapter.`in`.PingController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles

/**
 * Context-load smoke test. Runs against the `test` profile, which points at an
 * embedded H2 database in SQL Server compatibility mode, so no live SQL Server
 * is required.
 */
@SpringBootTest
@ActiveProfiles("test")
class HiveApplicationTests {

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `application context loads`() {
        assertThat(context).isNotNull
    }

    @Test
    fun `component scanning reaches the backtick-escaped adapter in package`() {
        assertThat(context.getBean(PingController::class.java)).isNotNull
    }
}

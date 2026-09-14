package hive.adapter.`in`

import hive.adapter.`in`.model.PingResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Smoke-test controller. Exists to prove that Spring component scanning reaches
 * the backtick-escaped `hive.adapter.`in`` package. Real controllers land in
 * later issues.
 */
@RestController
class PingController {

    @GetMapping("/api/ping")
    fun ping(): PingResponse = PingResponse(status = "ok")
}

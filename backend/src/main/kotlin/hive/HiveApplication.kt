package hive

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class HiveApplication

fun main(args: Array<String>) {
    SpringApplication.run(HiveApplication::class.java, *args)
}

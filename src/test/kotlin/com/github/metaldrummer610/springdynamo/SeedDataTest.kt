package com.github.metaldrummer610.springdynamo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.Month
import java.util.*

val product1Id: UUID = UUID.randomUUID()
val product2Id: UUID = UUID.randomUUID()
val product3Id: UUID = UUID.randomUUID()

@SpringBootTest
class SeedDataTest {
    @Autowired
    lateinit var productRepository: ProductRepo

    @Test
    fun `seed data should have run`() {
        val prod1s = productRepository.findAll(product1Id)
        assertThat(prod1s).hasSize(3)
        assertThat(prod1s).extracting("price", BigDecimal::class.java)
            .containsExactlyInAnyOrder(BigDecimal(1), BigDecimal(2), BigDecimal(5))
    }
}

typealias ProductRepo = CompositeKeyRepository<Product, UUID, LocalDateTime>

@DynamoTable
data class Product(
    @PartitionKey val id: UUID,
    @SortKey val asOf: LocalDateTime,
    val name: String,
    val price: BigDecimal
)

@SeedInfo(1, Product::class)
class V1Product(private val productRepo: ProductRepo) : SeedData {
    override fun execute() {
        productRepo.save(Product(product1Id, LocalDateTime.of(2020, Month.JANUARY, 1, 0, 0), "Test1", BigDecimal("1.00")))
        productRepo.save(Product(product2Id, LocalDateTime.of(2020, Month.JANUARY, 1, 0, 0), "Test2", BigDecimal("1.00")))
        productRepo.save(Product(product3Id, LocalDateTime.of(2020, Month.JANUARY, 1, 0, 0), "Test3", BigDecimal("1.00")))
    }
}

@SeedInfo(2, Product::class)
class V2Product(private val productRepo: ProductRepo) : SeedData {
    override fun execute() {
        productRepo.save(Product(product1Id, LocalDateTime.of(2020, Month.FEBRUARY, 1, 0, 0), "Test1", BigDecimal("2.00")))
        productRepo.save(Product(product2Id, LocalDateTime.of(2020, Month.FEBRUARY, 1, 0, 0), "Test2", BigDecimal("3.00")))
        productRepo.save(Product(product3Id, LocalDateTime.of(2020, Month.FEBRUARY, 1, 0, 0), "Test3", BigDecimal("4.00")))
    }
}

@SeedInfo(3, Product::class)
class V3Product(private val productRepo: ProductRepo) : SeedData {
    override fun execute() {
        productRepo.save(Product(product1Id, LocalDateTime.of(2020, Month.MARCH, 1, 0, 0), "Test1", BigDecimal("5.00")))
        productRepo.save(Product(product2Id, LocalDateTime.of(2020, Month.MARCH, 1, 0, 0), "Test2", BigDecimal("6.00")))
        productRepo.save(Product(product3Id, LocalDateTime.of(2020, Month.MARCH, 1, 0, 0), "Test3", BigDecimal("7.00")))
    }
}

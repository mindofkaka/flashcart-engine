package com.flashcart.engine.repository;

import com.flashcart.engine.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // Strategi ATOMIC: cek dan kurangi dalam SATU statement SQL.
    // Return 0 = produk tidak ada ATAU stok kurang.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Product p
           set p.stock = p.stock - :qty,
               p.version = p.version + 1
         where p.id = :id and p.stock >= :qty
        """)
    int decrementStockIfAvailable(@Param("id") Long id, @Param("qty") int qty);

    // Strategi PESSIMISTIC: SELECT ... FOR UPDATE
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Product p
        set p.stock = p.stock + :qty,
            p.version = p.version + 1
        where p.id = :id
        """)
    int incrementStock(@Param("id") Long id, @Param("qty") int qty);
}
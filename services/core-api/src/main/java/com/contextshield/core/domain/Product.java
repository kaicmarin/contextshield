package com.contextshield.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;


@Entity
@Table(name = "product")
@Getter
@Setter
@NoArgsConstructor
public class Product {
    @Id
    private String id;
    private String sku;
    private String name;
    private String line;
    private String categorySlug;
    private String sellerId;
    private BigDecimal price;
    private BigDecimal oldPrice;
    private Integer installments;
    private BigDecimal rating;
    private Integer reviewCount;
    private int stock;
    private Integer deliveryDays;
    private String image;
    private String tag;
    private String description;
}

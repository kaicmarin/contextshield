package com.contextshield.core.web;

import com.contextshield.core.domain.*;
import com.contextshield.core.repo.*;
import com.contextshield.core.service.ReferenceData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Catálogo público da loja NEXA. É compartilhado de propósito entre as organizações da demonstração:
 * produto, categoria e vendedor não são dado privado de uma empresa.
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final SellerRepository sellers;
    private final AddressRepository addresses;
    private final ReferenceData ref;

    public CatalogController(CategoryRepository categories, ProductRepository products, SellerRepository sellers,
                             AddressRepository addresses, ReferenceData ref) {
        this.categories = categories;
        this.products = products;
        this.sellers = sellers;
        this.addresses = addresses;
        this.ref = ref;
    }

    @GetMapping("/categories")
    public List<Category> categories() {
        return categories.findAll(org.springframework.data.domain.Sort.by("name"));
    }

    @GetMapping("/products")
    public List<Product> products(@RequestParam(required = false) String q, @RequestParam(required = false) String category) {
        List<Product> list;
        if (q != null && !q.isBlank()) list = products.findByNameContainingIgnoreCase(q.trim());
        else if (category != null && !category.isBlank()) list = products.findByCategorySlug(category);
        else list = products.findAll();
        return list.stream().sorted(Comparator.comparing(Product::getId)).toList();
    }

    public record ProductDetail(Product product, Seller seller, Category category) {}

    @GetMapping("/products/{id}")
    public ProductDetail product(@PathVariable String id) {
        Product p = products.findById(id).orElseThrow(() -> ApiException.notFound("Produto"));
        return new ProductDetail(p, sellers.findById(p.getSellerId()).orElse(null), categories.findById(p.getCategorySlug()).orElse(null));
    }

    @GetMapping("/sellers/{id}")
    public Map<String, Object> seller(@PathVariable String id) {
        Seller s = sellers.findById(id).orElseThrow(() -> ApiException.notFound("Vendedor"));
        return Map.of("seller", s, "products", products.findBySellerId(id));
    }

    @GetMapping("/pickup-points")
    public List<Address> pickupPoints() {
        return addresses.findByKind("pickup");
    }

    @GetMapping("/scenarios")
    public JsonNode scenarios() {
        return ref.get("scenarios");
    }

    @GetMapping("/options")
    public Map<String, Object> options() {
        return Map.of("declaredOrigins", ref.get("declaredOriginOptions"),
                "incidentTypes", ref.get("incidentTypes"),
                "incidentChannels", ref.get("incidentChannels"));
    }
}

package com.omni.platform.modules.scheduler.constants;

import java.util.List;
import java.util.Map;

import com.omni.platform.modules.scheduler.entities.Sector;

public final class SectorSeedConfig {

    public static final String DEFAULT_TAXONOMY = "ICB";
    public static final int DEFAULT_LEVEL = 3;

    public static final List<SectorSeed> SECTOR_SEEDS = List.of(
            new SectorSeed("BANKING", "Ngân hàng", "Banks", DEFAULT_TAXONOMY, DEFAULT_LEVEL, "8350"),
            new SectorSeed("SECURITIES", "Dịch vụ tài chính", "Financial Services", DEFAULT_TAXONOMY, DEFAULT_LEVEL,
                    "8770"),
            new SectorSeed("REAL_ESTATE", "Bất động sản", "Real Estate", DEFAULT_TAXONOMY, DEFAULT_LEVEL, "8630"),
            new SectorSeed("INSURANCE", "Bảo hiểm", "Insurance", DEFAULT_TAXONOMY, DEFAULT_LEVEL, "8530"),
            new SectorSeed("BASIC_RESOURCES", "Tài nguyên cơ bản", "Basic Resources", DEFAULT_TAXONOMY, DEFAULT_LEVEL,
                    "1750"),
            new SectorSeed("CONSTRUCTION_MATERIALS", "Xây dựng và Vật liệu", "Construction and Materials",
                    DEFAULT_TAXONOMY, DEFAULT_LEVEL, "2350"),
            new SectorSeed("INDUSTRIAL_GOODS_SERVICES", "Hàng hóa và Dịch vụ công nghiệp",
                    "Industrial Goods and Services", DEFAULT_TAXONOMY, DEFAULT_LEVEL, "2750"),
            new SectorSeed("AUTOMOBILES_PARTS", "Ô tô và Phụ tùng", "Automobiles and Parts", DEFAULT_TAXONOMY,
                    DEFAULT_LEVEL, "3350"),
            new SectorSeed("FOOD_BEVERAGE", "Thực phẩm và Đồ uống", "Food and Beverage", DEFAULT_TAXONOMY,
                    DEFAULT_LEVEL, "3570"),
            new SectorSeed("PERSONAL_HOUSEHOLD_GOODS", "Hàng cá nhân và Gia dụng", "Personal and Household Goods",
                    DEFAULT_TAXONOMY, DEFAULT_LEVEL, "3760"),
            new SectorSeed("HEALTH_CARE", "Y tế", "Health Care", DEFAULT_TAXONOMY, DEFAULT_LEVEL, "4530"),
            new SectorSeed("RETAIL", "Bán lẻ", "Retail", DEFAULT_TAXONOMY, DEFAULT_LEVEL, "5370"),
            new SectorSeed("MEDIA", "Truyền thông", "Media", DEFAULT_TAXONOMY, DEFAULT_LEVEL, "5550"),
            new SectorSeed("TRAVEL_LEISURE", "Du lịch và Giải trí", "Travel and Leisure", DEFAULT_TAXONOMY,
                    DEFAULT_LEVEL, "5750"),
            new SectorSeed("TELECOMMUNICATIONS", "Viễn thông", "Telecommunications", DEFAULT_TAXONOMY, DEFAULT_LEVEL,
                    "6530"),
            new SectorSeed("UTILITIES", "Tiện ích", "Utilities", DEFAULT_TAXONOMY, DEFAULT_LEVEL, "7570"),
            new SectorSeed("TECHNOLOGY", "Công nghệ", "Technology", DEFAULT_TAXONOMY, DEFAULT_LEVEL, "9530"));

    private SectorSeedConfig() {
    }

    public record SectorSeed(String code, String nameVi, String nameEn, String taxonomy, Integer taxonomyLevel,
            String sourceCode) {

        public Sector toEntity() {
            Sector sector = new Sector();
            sector.setCode(code);
            sector.setNameVi(nameVi);
            sector.setNameEn(nameEn);
            sector.setTaxonomy(taxonomy);
            sector.setTaxonomyLevel(taxonomyLevel);
            sector.setSourceCode(sourceCode);
            sector.setIsActive(true);
            sector.setMetaJson(Map.of("seedSource", "java"));
            return sector;
        }
    }
}
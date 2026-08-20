package com.omni.platform.modules.scheduler.constants;

import java.util.List;
import java.util.Map;

import com.omni.platform.modules.scheduler.entities.Sector;

public final class SectorSeedConfig {

        public static final String DEFAULT_TAXONOMY = "ICB";
        public static final int DEFAULT_LEVEL = 2;

        public static final String FOOD_AND_BEVERAGE = "FOOD_AND_BEVERAGE";
        public static final String CHEMICALS = "CHEMICALS";
        public static final String FINANCIAL_SERVICES = "FINANCIAL_SERVICES";
        public static final String TRAVEL_AND_LEISURE = "TRAVEL_AND_LEISURE";
        public static final String INDUSTRIAL_GOODS_AND_SERVICES = "INDUSTRIAL_GOODS_AND_SERVICES";
        public static final String TECHNOLOGY = "TECHNOLOGY";
        public static final String BASIC_RESOURCES = "BASIC_RESOURCES";
        public static final String CONSTRUCTION_AND_MATERIALS = "CONSTRUCTION_AND_MATERIALS";
        public static final String OIL_AND_GAS = "OIL_AND_GAS";
        public static final String PERSONAL_AND_HOUSEHOLD_GOODS = "PERSONAL_AND_HOUSEHOLD_GOODS";
        public static final String MEDIA = "MEDIA";
        public static final String REAL_ESTATE = "REAL_ESTATE";
        public static final String TELECOMMUNICATIONS = "TELECOMMUNICATIONS";
        public static final String UTILITIES = "UTILITIES";
        public static final String RETAIL = "RETAIL";
        public static final String HEALTH_CARE = "HEALTH_CARE";
        public static final String AUTOMOBILES_AND_PARTS = "AUTOMOBILES_AND_PARTS";
        public static final String BANKS = "BANKS";
        public static final String INSURANCE = "INSURANCE";

        public static final List<SectorSeed> SECTOR_SEEDS = List.of(
                        seed(FOOD_AND_BEVERAGE, "Thực phẩm và Đồ uống", "Food and Beverage", "3500"),
                        seed(CHEMICALS, "Hóa chất", "Chemicals", "1300"),
                        seed(FINANCIAL_SERVICES, "Dịch vụ tài chính", "Financial Services", "8700"),
                        seed(TRAVEL_AND_LEISURE, "Du lịch và Giải trí", "Travel and Leisure", "5700"),
                        seed(INDUSTRIAL_GOODS_AND_SERVICES, "Hàng hóa và Dịch vụ công nghiệp",
                                        "Industrial Goods and Services", "2700"),
                        seed(TECHNOLOGY, "Công nghệ", "Technology", "9500"),
                        seed(BASIC_RESOURCES, "Tài nguyên cơ bản", "Basic Resources", "1700"),
                        seed(CONSTRUCTION_AND_MATERIALS, "Xây dựng và Vật liệu", "Construction and Materials", "2300"),
                        seed(OIL_AND_GAS, "Dầu khí", "Oil and Gas", "0500"),
                        seed(PERSONAL_AND_HOUSEHOLD_GOODS, "Hàng cá nhân và Gia dụng",
                                        "Personal and Household Goods", "3700"),
                        seed(MEDIA, "Truyền thông", "Media", "5500"),
                        seed(REAL_ESTATE, "Bất động sản", "Real Estate", "8600"),
                        seed(TELECOMMUNICATIONS, "Viễn thông", "Telecommunications", "6500"),
                        seed(UTILITIES, "Tiện ích", "Utilities", "7500"),
                        seed(RETAIL, "Bán lẻ", "Retail", "5300"),
                        seed(HEALTH_CARE, "Y tế", "Health Care", "4500"),
                        seed(AUTOMOBILES_AND_PARTS, "Ô tô và Phụ tùng", "Automobiles and Parts", "3300"),
                        seed(BANKS, "Ngân hàng", "Banks", "8300"),
                        seed(INSURANCE, "Bảo hiểm", "Insurance", "8500"));

        private static SectorSeed seed(String code, String nameVi, String nameEn, String sourceCode) {
                return new SectorSeed(code, nameVi, nameEn, DEFAULT_TAXONOMY, DEFAULT_LEVEL, sourceCode);
        }

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
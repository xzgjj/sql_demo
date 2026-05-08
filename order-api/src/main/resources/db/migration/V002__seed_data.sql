-- V002: 种子数据
-- MiniDB-Lab Phase 4: 初始化商品、库存、用户、仓库

-- 初始化商品（咖啡豆、纸巾、杯子、笔记本、茶包）
INSERT INTO products (id, category_id, sku, name, price, status) VALUES
(1001, 1, 'COFFEE-001', '咖啡豆（深烘）', 89.00, 1),
(1002, 1, 'COFFEE-002', '咖啡豆（中烘）', 69.00, 1),
(1003, 2, 'TISSUE-001', '纸巾（盒装）', 12.90, 1),
(1004, 3, 'CUP-001', '保温杯', 129.00, 1),
(1005, 4, 'NOTE-001', '笔记本 A5', 25.00, 1),
(1006, 5, 'TEA-001', '绿茶茶包', 45.00, 1);

-- 初始化库存（每种商品初始库存100件）
INSERT INTO product_inventory (product_id, available_qty, locked_qty, shipped_qty, version) VALUES
(1001, 100, 0, 0, 0),
(1002, 80, 0, 0, 0),
(1003, 200, 0, 0, 0),
(1004, 50, 0, 0, 0),
(1005, 150, 0, 0, 0),
(1006, 120, 0, 0, 0);

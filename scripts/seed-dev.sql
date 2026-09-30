-- ==============================================================
-- DEV SEED for hsm-sim (default SIM_LMK). TEST DATA ONLY.
-- Regenerate key rows with: java hsm-sim/HsmSimulator.java seed
-- ==============================================================
BEGIN;
DELETE FROM hsm_key WHERE key_name IN ('ZMK_COREHOST','ZPK_KIOSK','ZPK_COREHOST','PVK_P01','CVK_P01','IMK_AC_P01');
-- hsm-sim 1.0.0 test keys (CLEAR values are for TEST ONLY)
-- ZMK_COREHOST  clear=1C1C1C1C1C1C1C1C2A2A2A2A2A2A2A2A kcv=B29243
-- ZPK_KIOSK     clear=0B0B0B0B0B0B0B0B1616161616161616 kcv=FB57EE
-- ZPK_COREHOST  clear=4C4C4C4C4C4C4C4C5E5E5E5E5E5E5E5E kcv=266B8A
-- PVK_P01       clear=FEDCBA98765432100123456789ABCDEF kcv=7B8358
-- CVK_P01       clear=0123456789ABCDEFFEDCBA9876543210 kcv=08D7B4
-- IMK_AC_P01    clear=4A4A4A4A4A4A4A4A6D6D6D6D6D6D6D6D kcv=437134
INSERT INTO hsm_key (key_name, key_type, key_scheme, key_under_lmk, kcv) VALUES
 ('ZMK_COREHOST','ZMK','U','UFB2005460F0371BED0A63FA20658651E','B29243'),
 ('ZPK_KIOSK','ZPK','U','UE1649ED60A0FBA6BFEE044A2C86CB343','FB57EE'),
 ('ZPK_COREHOST','ZPK','U','U09006E291A9B0F1EC4188E99492A43AD','266B8A'),
 ('PVK_P01','PVK','U','UE37975838EFAF583692E1F6E8C6A0A55','7B8358'),
 ('CVK_P01','CVK','U','U692E1F6E8C6A0A55E37975838EFAF583','08D7B4'),
 ('IMK_AC_P01','IMK_AC','U','U29CDA3BAACABAFEF4765416FAA5878C7','437134');

INSERT INTO card_product (code, name, bin, pan_length, range_start, range_end, next_sequence,
    service_code, validity_months, chip_profile, currency_code, pvki, pvk_key_name, cvk_key_name,
    daily_wd_amount, per_txn_wd_max, card_type, card_tier, scheme, max_cards_per_account)
VALUES
 ('P01', 'Prepaid Classic EGP', '999999', 16, 1,         499999999, 1,
  '221', 36, 'TEST_PROFILE', 'EGP', '1', 'PVK_P01', 'CVK_P01', 2000000, 1000000, 'PREPAID', 'CLASSIC', 'MEEZA', 1),
 ('P02', 'Debit Gold EGP',      '999999', 16, 500000000, 999999999, 500000000,
  '221', 36, 'TEST_PROFILE', 'EGP', '1', 'PVK_P01', 'CVK_P01', 5000000, 2000000, 'DEBIT',   'GOLD',    'MEEZA', 2)
ON CONFLICT (code) DO NOTHING;

-- chip cryptograms (hsm-sim 1.1.0 KQ): both products use the test IMK-AC
UPDATE card_product SET imk_ac_key_name = 'IMK_AC_P01' WHERE code IN ('P01','P02');

INSERT INTO product_eligibility (product_id, account_type_code, segment_code)
SELECT p.id, t.code, s.code FROM card_product p, account_type t, customer_segment s
 WHERE (p.code = 'P01' AND t.code IN ('PREPAID','PAYROLL') AND s.code IN ('MASS','PAYROLL','STAFF'))
    OR (p.code = 'P02' AND t.code IN ('CURRENT','SAVINGS')  AND s.code IN ('PREMIUM','STAFF'))
ON CONFLICT DO NOTHING;

COMMIT;

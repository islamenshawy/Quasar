-- ==============================================================
-- DEV SEED for hsm-sim (default SIM_LMK). TEST DATA ONLY.
-- Regenerate key rows with: java hsm-sim/HsmSimulator.java seed
-- ==============================================================
BEGIN;
DELETE FROM hsm_key WHERE key_name IN ('ZMK_COREHOST','ZPK_KIOSK','ZPK_COREHOST','PVK_P01','CVK_P01','IMK_AC_P01','IMK_SMI_P01',
                                       'ZPK_CHANNEL','CAVV_P01');
-- hsm-sim 1.0.0 test keys (CLEAR values are for TEST ONLY)
-- ZMK_COREHOST  clear=1C1C1C1C1C1C1C1C2A2A2A2A2A2A2A2A kcv=B29243
-- ZPK_KIOSK     clear=0B0B0B0B0B0B0B0B1616161616161616 kcv=FB57EE
-- ZPK_COREHOST  clear=4C4C4C4C4C4C4C4C5E5E5E5E5E5E5E5E kcv=266B8A
-- PVK_P01       clear=FEDCBA98765432100123456789ABCDEF kcv=7B8358
-- CVK_P01       clear=0123456789ABCDEFFEDCBA9876543210 kcv=08D7B4
-- IMK_AC_P01    clear=4A4A4A4A4A4A4A4A6D6D6D6D6D6D6D6D kcv=437134
-- IMK_SMI_P01   clear=5A5A5A5A5A5A5A5A3C3C3C3C3C3C3C3C kcv=5595A4 (issuer scripts, hsm-sim 1.2.0)
-- ZPK_CHANNEL   clear=2D2D2D2D2D2D2D2D7A7A7A7A7A7A7A7A kcv=FAB032 (PIN set in the cardholder app, CMS-115)
-- CAVV_P01      clear=6E6E6E6E6E6E6E6E1F1F1F1F1F1F1F1F kcv=03F7C7 (3-D Secure CAVV, CMS-115)
INSERT INTO hsm_key (key_name, key_type, key_scheme, key_under_lmk, kcv) VALUES
 ('ZMK_COREHOST','ZMK','U','UFB2005460F0371BED0A63FA20658651E','B29243'),
 ('ZPK_KIOSK','ZPK','U','UE1649ED60A0FBA6BFEE044A2C86CB343','FB57EE'),
 ('ZPK_COREHOST','ZPK','U','U09006E291A9B0F1EC4188E99492A43AD','266B8A'),
 ('PVK_P01','PVK','U','UE37975838EFAF583692E1F6E8C6A0A55','7B8358'),
 ('CVK_P01','CVK','U','U692E1F6E8C6A0A55E37975838EFAF583','08D7B4'),
 ('IMK_AC_P01','IMK_AC','U','U29CDA3BAACABAFEF4765416FAA5878C7','437134'),
 ('IMK_SMI_P01','IMK_SMI','U','U99F1BC6755ABCC0DED5E925FE9404323','5595A4'),
 ('ZPK_CHANNEL','ZPK','U','U01EDAFA72C4331E15A5295417D5F2CD5','FAB032'),
 ('CAVV_P01','CVK','U','U31F5A5096A14BA9BF0EA63D1A58CED4C','03F7C7');

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

-- core banking funds interface (CMS-090): an account type whose money lives in core banking
-- (the dev profile points cms.core-banking at the in-process simulator /api/dev/core-sim).
-- Account numbers come from core; card product P02 may be issued on it and stands in up to 500.00.
INSERT INTO account_type (code, name, ledger_mode, number_source)
VALUES ('CORE_CURRENT', 'Current account (core banking)', 'CORE_BANKING', 'CORE_BANKING')
ON CONFLICT (code) DO NOTHING;
INSERT INTO account_type_currency (account_type_code, currency_code) VALUES ('CORE_CURRENT', 'EGP')
ON CONFLICT DO NOTHING;
INSERT INTO product_eligibility (product_id, account_type_code, segment_code)
SELECT p.id, 'CORE_CURRENT', s.code FROM card_product p, customer_segment s
 WHERE p.code = 'P02' AND s.code IN ('PREMIUM','STAFF','MASS')
ON CONFLICT DO NOTHING;
UPDATE card_product SET core_stip_limit = 50000 WHERE code = 'P02';

-- chip completeness (CMS-110): issuer scripts signed with IMK_SMI_P01; P01 contactless: 5,000.00 per tap,
-- PIN above 600.00, 2,000.00 without a PIN before a PIN is asked again
UPDATE card_product SET imk_smi_key_name = 'IMK_SMI_P01' WHERE code IN ('P01','P02');
UPDATE card_product SET contactless_txn_limit = 500000, contactless_cvm_limit = 60000, contactless_cumulative_limit = 200000 WHERE code = 'P01';

-- digital channels (CMS-115): both products can go into wallets (up to 5 tokens) and use the bank's ACS with the
-- test CAVV key; e-commerce up to 500.00 is frictionless when nothing else looks risky
UPDATE card_product SET token_enabled = TRUE, token_max_per_card = 5, tds_enabled = TRUE, cavv_key_name = 'CAVV_P01',
       tds_frictionless_max = 50000
 WHERE code IN ('P01','P02');

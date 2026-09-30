-- =====================================================================
-- V8: EMV chip cryptograms (CMS-057)
--   ARQC verification / ARPC generation through the HSM (KQ) when a product has an IMK-AC key.
--   The data block for the cryptogram is built from field 55 in the order of emv_data_list,
--   which must match the card's CDOL1 (chip profile). Tokens: a tag (e.g. 9F02) takes its whole
--   value; 9F10:CVR takes the Visa CVR bytes (4..7) of the issuer application data.
-- =====================================================================

ALTER TABLE card_product
    ADD COLUMN emv_scheme    VARCHAR(12)  NOT NULL DEFAULT 'EMV_CSK' CHECK (emv_scheme IN ('VISA_CVN10','EMV_CSK')),
    ADD COLUMN emv_data_list VARCHAR(256) NOT NULL DEFAULT '9F02,9F03,9F1A,95,5F2A,9A,9C,9F37,82,9F36,9F10:CVR';

-- highest application transaction counter seen per card, to refuse replayed chip cryptograms
ALTER TABLE card ADD COLUMN last_atc INT;

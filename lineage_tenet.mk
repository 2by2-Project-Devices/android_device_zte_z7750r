#
# Copyright (C) 2021 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit from tenet device
$(call inherit-product, device/zte/tenet/device.mk)

# Inherit some common Lineage stuff.
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

# Device identifier. This must come after all inclusions.
PRODUCT_BRAND := ZTE
PRODUCT_DEVICE := tenet
PRODUCT_MANUFACTURER := ZTE
PRODUCT_MODEL := ZTE A2122H
PRODUCT_NAME := lineage_tenet

PRODUCT_GMS_CLIENTID_BASE := android-zte

PRODUCT_BUILD_PROP_OVERRIDES += \
    PRIVATE_BUILD_DESC="CN_P768A02-user 11 RKQ1.210303.002 20231225.163043 release-keys" \
    TARGET_DEVICE="P768A02" \
    TARGET_PRODUCT="CN_P768A02"

# Set BUILD_FINGERPRINT variable to be picked up by both system and vendor build.prop
BUILD_FINGERPRINT := "ZTE/CN_P768A02/P768A02:11/RKQ1.210303.002/20231225.163043:user/release-keys"

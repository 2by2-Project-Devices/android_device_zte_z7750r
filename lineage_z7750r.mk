#
# SPDX-FileCopyrightText: 2021-2024 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
TARGET_SUPPORTS_OMX_SERVICE := false
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit from z7750r device
$(call inherit-product, device/zte/z7750r/device.mk)

# Inherit some common Lineage stuff.
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

# Device identifier. This must come after all inclusions.
PRODUCT_BRAND := ZTE
PRODUCT_DEVICE := z7750r
PRODUCT_MANUFACTURER := ZTE
PRODUCT_MODEL := ZTE Z7750R
PRODUCT_NAME := lineage_z7750r

PRODUCT_GMS_CLIENTID_BASE := android-zte

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="Z7750R-user 10 QKQ1.200768.002 20221013.223009 release-keys" \
    BuildFingerprint=ZTE/Z7750R/Z7750R:10/QKQ1.200768.002/20221013.223009:user/release-keys \
    DeviceName=Z7750R \
    DeviceProduct=Z7750R

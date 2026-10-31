package com.gluview.app.watchface

import com.gluview.app.data.local.entity.WatchFaceEntity

fun WatchFaceEntity.toDesign(): WatchFaceDesign =
    WatchFaceMigrations.migrate(
        WatchFaceDesign(
            id = id,
            name = name,
            wearable = WearableCatalog.find(
                id = wearableId,
                width = wearableWidthPx,
                height = wearableHeightPx,
                name = wearableName
            ),
            createdAt = createdAt,
            updatedAt = updatedAt,
            components = WatchFaceJson.decodeComponents(componentsJson),
            schemaVersion = schemaVersion,
            compatibleWearableIds = WatchFaceJson.decodeIdList(compatibleWearableIdsJson)
        )
    )

fun WatchFaceDesign.toEntity(): WatchFaceEntity =
    WatchFaceEntity(
        id = id,
        name = name,
        wearableId = wearable.id,
        wearableName = wearable.name,
        wearableWidthPx = wearable.widthPx,
        wearableHeightPx = wearable.heightPx,
        createdAt = createdAt,
        updatedAt = updatedAt,
        componentsJson = WatchFaceJson.encodeComponents(components),
        schemaVersion = schemaVersion,
        compatibleWearableIdsJson = WatchFaceJson.encodeIdList(compatibleWearableIds)
    )

package botamochi129.botamochi.rcap.client.render;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.LivingEntity;

/**
 * プレイヤーモデルを使って、Entityなしで乗客を描画する（null安全）
 */
public class PassengerModel extends PlayerEntityModel<LivingEntity> {

    public PassengerModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    @Override
    public void setAngles(LivingEntity entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
        // Entityがnullでもクラッシュしないように固定角度
        this.head.yaw = headYaw * 0.01745F;
        this.head.pitch = headPitch * 0.01745F;

        this.child = false;
        // 棒立ち（動かない）の場合は以下省略でOKだが、動かしたいならここで腕・足設定可
        this.leftArm.yaw = 0;
        this.rightArm.yaw = 0;
        this.leftLeg.yaw = 0;
        this.rightLeg.yaw = 0;
    }
}

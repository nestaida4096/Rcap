package botamochi129.botamochi.rcap.passenger;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PassengerPathfinder {
    private static final int MAX_SEARCH_NODES = 4096;
    private static final int MAX_PATH_LENGTH = 128;

    // 経路探索専用のスレッドプール（メインスレッドの負荷を完全に切り離します）
    private static final ExecutorService PATHFINDING_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 4)
    );

    private PassengerPathfinder() {
    }

    /**
     * 重い経路探索と経路の直線化処理（ポスト処理）をまとめてバックグラウンドで非同期実行します。
     */
    public static CompletableFuture<List<BlockPos>> findPathAsync(BlockView world, BlockPos start, BlockPos goal, int maxDistance) {
        return CompletableFuture.supplyAsync(() -> findPath(world, start, goal, maxDistance), PATHFINDING_EXECUTOR);
    }

    /**
     * スナップショットから経路を検索し、最適化された経路リストを返します。
     */
    public static List<BlockPos> findPath(BlockView world, BlockPos start, BlockPos goal, int maxDistance) {
        if (start.isWithinDistance(goal, 1.5)) {
            return List.of(goal);
        }

        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingDouble(node -> node.fScore));
        Map<BlockPos, Node> allNodes = new HashMap<>();
        Set<BlockPos> closedSet = new HashSet<>();

        Node startNode = new Node(start, null, 0.0, heuristic(start, goal));
        openSet.add(startNode);
        allNodes.put(start, startNode);

        int expanded = 0;
        while (!openSet.isEmpty() && expanded < MAX_SEARCH_NODES) {
            Node current = openSet.poll();
            if (current == null || closedSet.contains(current.pos)) {
                continue;
            }
            if (current.pos.isWithinDistance(goal, 1.5)) {
                List<BlockPos> rawPath = buildPath(current, goal);
                // 生成されたジグザグなA*パスを、バックグラウンドスレッド上で滑らかな直線パスに単純化します
                return simplifyPath(world, rawPath);
            }

            closedSet.add(current.pos);
            expanded++;

            for (BlockPos next : getNeighbors(world, current.pos, start, maxDistance)) {
                if (closedSet.contains(next)) {
                    continue;
                }

                double cost = distance(current.pos, next);

                if (current.pos.getY() != next.getY()) {
                    boolean isStairs = isRealStairs(world, current.pos.down()) || isRealStairs(world, next.down());
                    if (!isStairs) {
                        cost += 5.0;
                    } else {
                        cost *= 0.5;
                    }
                }

                double tentativeG = current.gScore + cost;
                Node existing = allNodes.get(next);
                if (existing == null || tentativeG < existing.gScore) {
                    Node updated = new Node(next, current, tentativeG, tentativeG + heuristic(next, goal));
                    allNodes.put(next, updated);
                    openSet.add(updated);
                }
            }
        }

        return List.of(goal);
    }

    private static List<BlockPos> buildPath(Node node, BlockPos goal) {
        List<BlockPos> result = new ArrayList<>();
        Node current = node;
        while (current != null) {
            result.add(current.pos);
            current = current.parent;
        }
        Collections.reverse(result);
        if (result.isEmpty() || !result.get(result.size() - 1).equals(goal)) {
            result.add(goal);
        }
        if (result.size() > MAX_PATH_LENGTH) {
            return new ArrayList<>(result.subList(0, MAX_PATH_LENGTH));
        }
        return result;
    }

    private static Iterable<BlockPos> getNeighbors(BlockView world, BlockPos pos, BlockPos start, int maxDistance) {
        List<BlockPos> neighbors = new ArrayList<>(12);
        int[][] offsets = new int[][]{
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };

        for (int[] offset : offsets) {
            // 斜め移動時の「角抜け（壁や柵のすり抜け）」を防止する3D空間判定
            if (offset[0] != 0 && offset[1] != 0) {
                BlockPos p1_feet = pos.add(offset[0], 0, 0);
                BlockPos p1_head = pos.add(offset[0], 1, 0);
                BlockPos p2_feet = pos.add(0, 0, offset[1]);
                BlockPos p2_head = pos.add(0, 1, offset[1]);

                boolean blocked1 = !world.getBlockState(p1_feet).getCollisionShape(world, p1_feet).isEmpty() ||
                        !world.getBlockState(p1_head).getCollisionShape(world, p1_head).isEmpty();
                boolean blocked2 = !world.getBlockState(p2_feet).getCollisionShape(world, p2_feet).isEmpty() ||
                        !world.getBlockState(p2_head).getCollisionShape(world, p2_head).isEmpty();

                if (blocked1 || blocked2) {
                    continue;
                }
            }

            for (int dy = -1; dy <= 1; dy++) {
                BlockPos next = pos.add(offset[0], dy, offset[1]);
                if (next.getManhattanDistance(start) > maxDistance) {
                    continue;
                }
                if (isWalkable(world, next)) {
                    neighbors.add(next);
                    break;
                }
            }
        }
        return neighbors;
    }

    // ★改善: PassengerMovement側のロジックと完全に同期した3D空間のクリアチェック判定
    public static boolean isSpaceClearAt(BlockView world, double x, double y, double z) {
        BlockPos feetPos = new BlockPos(x, y, z);
        BlockState feetState = world.getBlockState(feetPos);
        if (!feetState.getCollisionShape(world, feetPos).isEmpty()) {
            double maxCollisionY = feetState.getCollisionShape(world, feetPos).getMax(net.minecraft.util.math.Direction.Axis.Y);
            if (!Double.isNaN(maxCollisionY)) {
                double absoluteCollisionTop = feetPos.getY() + maxCollisionY;
                // 足元の当たり判定が乗客の座標（段差高さ）より少しでも高い場合は通れないと判定
                if (absoluteCollisionTop > y + 0.05) {
                    return false;
                }
            }
        }

        BlockPos headPos = new BlockPos(x, y + 1.0, z);
        BlockState headState = world.getBlockState(headPos);
        if (!headState.getCollisionShape(world, headPos).isEmpty()) {
            double minCollisionY = headState.getCollisionShape(world, headPos).getMin(net.minecraft.util.math.Direction.Axis.Y);
            if (!Double.isNaN(minCollisionY)) {
                double absoluteCollisionBottom = headPos.getY() + minCollisionY;
                // 頭上に天井があり、身長1.8ブロック以下に干渉する場合は通れない
                if (absoluteCollisionBottom < y + 1.8) {
                    return false;
                }
            }
        }

        return true;
    }

    // ★改善: isWalkable 内の判定を 3D の isSpaceClearAt 判定に統合。
    // ハーフブロックや階段を障害物とみなす不具合を100%解決します。
    private static boolean isWalkable(BlockView world, BlockPos pos) {
        BlockPos feet = pos;
        BlockPos head = pos.up();
        BlockPos ground = pos.down();

        BlockState groundState = world.getBlockState(ground);

        // 柵(Fence)や壁(Wall)、フェンスゲートの上は除外
        if (groundState.isIn(BlockTags.FENCES) || groundState.isIn(BlockTags.WALLS) || groundState.getBlock() instanceof FenceGateBlock) {
            return false;
        }

        // 足場(ground)が空気であるか、衝突判定がない場合は落下するため歩行不可
        if (groundState.getCollisionShape(world, ground).isEmpty()) {
            return false;
        }

        // 地形の凹凸・段差（階段やハーフブロック）を考慮した仮想的な立ち位置Yの算出
        double standingY = pos.getY();
        double maxColY = groundState.getCollisionShape(world, ground).getMax(net.minecraft.util.math.Direction.Axis.Y);
        if (!Double.isNaN(maxColY)) {
            standingY = ground.getY() + maxColY;
        }

        // 算出された高さをベースに、1ブロック単位で頭上と足元の衝突空間が空いているかチェック
        return isSpaceClearAt(world, pos.getX() + 0.5, standingY, pos.getZ() + 0.5);
    }

    private static boolean isStairsOrSlab(BlockView world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        return block instanceof StairsBlock || block instanceof SlabBlock;
    }

    private static boolean isRealStairs(BlockView world, BlockPos pos) {
        if (!isStairsOrSlab(world, pos)) return false;

        int[][] directions = {{1,0}, {-1,0}, {0,1}, {0,-1}};
        for (int[] off : directions) {
            if (isStairsOrSlab(world, pos.add(off[0], 1, off[1]))) return true;
            if (isStairsOrSlab(world, pos.add(off[0], -1, off[1]))) return true;
        }
        return false;
    }

    /**
     * 経路上の不要なノードを間引き、直線的なショートカットにリファクタリング（単純化）します。
     */
    private static List<BlockPos> simplifyPath(BlockView world, List<BlockPos> path) {
        if (path.size() <= 2) {
            return path;
        }

        List<BlockPos> simplified = new ArrayList<>();
        simplified.add(path.get(0));

        int currentIdx = 0;
        while (currentIdx < path.size() - 1) {
            int nextTargetIdx = currentIdx + 1;

            // 現在のノードから見通せる（壁がない）最も遠くのノードを探す
            for (int i = currentIdx + 2; i < path.size(); i++) {
                if (hasLineOfSight(world, path.get(currentIdx), path.get(i))) {
                    nextTargetIdx = i;
                } else {
                    break; // 遮られたらそれ以上遠くは見ない
                }
            }

            simplified.add(path.get(nextTargetIdx));
            currentIdx = nextTargetIdx;
        }

        return simplified;
    }

    /**
     * 2つのブロック位置の間に、遮る壁や段差（高低差）がないか3次元的に等速走査（レイキャスト）します。
     */
    private static boolean hasLineOfSight(BlockView world, BlockPos start, BlockPos end) {
        if (Math.abs(start.getY() - end.getY()) > 1) {
            return false;
        }

        Vec3d startVec = new Vec3d(start.getX() + 0.5, start.getY() + 0.5, start.getZ() + 0.5);
        Vec3d endVec = new Vec3d(end.getX() + 0.5, end.getY() + 0.5, end.getZ() + 0.5);

        double distance = startVec.distanceTo(endVec);
        int steps = (int) Math.ceil(distance * 3.0); // 1ブロックあたり3分割でサンプリング

        for (int i = 1; i < steps; i++) {
            double pct = (double) i / steps;
            Vec3d interp = startVec.lerp(endVec, pct);
            BlockPos checkPos = new BlockPos(interp.x, interp.y, interp.z);

            // 線上の空間に衝突判定がある、または床がない場合は直線移動不可とする
            BlockState feetState = world.getBlockState(checkPos);
            BlockState headState = world.getBlockState(checkPos.up());
            BlockState groundState = world.getBlockState(checkPos.down());

            // 頭上や足元に衝突形状があるか（空間クリアチェック）
            if (!feetState.getCollisionShape(world, checkPos).isEmpty() ||
                    !headState.getCollisionShape(world, checkPos.up()).isEmpty()) {
                return false;
            }

            // 空中を歩いてしまわないよう、足場が完全に中空でないかチェック
            if (groundState.getCollisionShape(world, checkPos.down()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return distance(a, b);
    }

    private static double distance(BlockPos a, BlockPos b) {
        return Math.sqrt(a.getSquaredDistance(b));
    }

    private static final class Node {
        private final BlockPos pos;
        private final Node parent;
        private final double gScore;
        private final double fScore;

        private Node(BlockPos pos, Node parent, double gScore, double fScore) {
            this.pos = pos;
            this.parent = parent;
            this.gScore = gScore;
            this.fScore = fScore;
        }
    }
}
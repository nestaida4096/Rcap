package botamochi129.botamochi.rcap.passenger;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public final class PassengerPathfinder {
    private static final int MAX_SEARCH_NODES = 4096;
    private static final int MAX_PATH_LENGTH = 128;

    private PassengerPathfinder() {
    }

    public static List<BlockPos> findPath(ServerWorld world, BlockPos start, BlockPos goal, int maxDistance) {
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
                return buildPath(current, goal);
            }

            closedSet.add(current.pos);
            expanded++;

            for (BlockPos next : getNeighbors(world, current.pos, start, maxDistance)) {
                if (closedSet.contains(next)) {
                    continue;
                }

                double tentativeG = current.gScore + distance(current.pos, next);
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

    private static Iterable<BlockPos> getNeighbors(ServerWorld world, BlockPos pos, BlockPos start, int maxDistance) {
        List<BlockPos> neighbors = new ArrayList<>(12);
        int[][] offsets = new int[][]{
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };

        for (int[] offset : offsets) {
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

    private static boolean isWalkable(ServerWorld world, BlockPos pos) {
        BlockPos feet = pos;
        BlockPos head = pos.up();
        BlockPos ground = pos.down();
        return world.getBlockState(ground).isOpaqueFullCube(world, ground)
                && world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                && world.getBlockState(head).getCollisionShape(world, head).isEmpty();
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

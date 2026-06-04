"""Smoke + behaviour tests for the hub-ablation closure."""
import ablate_hub as A

# tiny graph: a menu M (hub) fans out to leaves X,Y via dispatch; leaves return to M.
EDGES = {"M": ["X", "Y"], "X": ["M"], "Y": ["M", "Z"], "Z": []}
CORPUS = {"M", "X", "Y", "Z"}


def test_normal_closure_makes_scc():
    # X reaches everything through M (the SCC), normal relation.
    assert A.closure(EDGES, "X", CORPUS, ablate=False) == {"M", "Y", "Z"}


def test_ablation_treats_hub_as_boundary():
    # With M in HUB, a leaf reaching M does not propagate through it.
    A.HUB = {"M"}
    assert A.closure(EDGES, "X", CORPUS, ablate=True) == {"M"}
    # Y reaches M (boundary, not expanded) and its own business child Z.
    assert A.closure(EDGES, "Y", CORPUS, ablate=True) == {"M", "Z"}


def test_seed_hub_still_expands():
    # M as the query seed still fans out (it owns the dispatch under test).
    A.HUB = {"M"}
    assert A.closure(EDGES, "M", CORPUS, ablate=True) == {"X", "Y", "Z"}


def test_jaccard_degeneracy_metric():
    assert A.mean_pairwise_jaccard([{1, 2, 3}, {1, 2, 3}]) == 1.0
    assert A.mean_pairwise_jaccard([{1}, {2}]) == 0.0

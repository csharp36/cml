"""TDD checks for the independent GnuCOBOL-normalized extractor.

Expected values are derived from CardDemo source by hand-reading the normalized
`.cob` files — NOT copied from oracle.json. They encode the load-bearing cases:
the bug-1 literal-XCTL site (COSGN00C), the OCCURS menu harvest (COMEN01C), the
constant-propagation chain (COACTUPC -> COMEN01C), and the bug-2 ddname identity
(CBTRN03C couples on physical ddname, not logical SELECT name).
"""
from pathlib import Path
import extract_edges as E

HERE = Path(__file__).resolve().parent
NORM = HERE / "normalized"
CORPUS = {f.stem.upper() for f in NORM.glob("*.cob")}


def edges(prog):
    return set(E.extract_program(NORM / f"{prog}.cob", CORPUS)["corpus_edges"])


def ddnames(prog):
    return set(E.extract_program(NORM / f"{prog}.cob", CORPUS)["ddnames"])


def test_corpus_is_44():
    assert len(CORPUS) == 44


def test_cosgn00c_literal_xctl_bug1():
    # The two literal `PROGRAM ('...')` (space-before-paren) sites must resolve.
    assert edges("COSGN00C") == {"COADM01C", "COMEN01C"}


def test_comen01c_occurs_menu_harvest():
    # 11 menu programs (from the expanded COMEN02Y OCCURS table) + COSGN00C.
    assert edges("COMEN01C") == {
        "COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
        "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C",
        "COPAUS0C", "COSGN00C",
    }


def test_coadm01c_admin_menu_harvest():
    assert edges("COADM01C") == {
        "COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C",
        "COTRTLIC", "COTRTUPC", "COSGN00C",
    }


def test_coactupc_const_prop_to_comen01c():
    # MOVE LIT-MENUPGM(=COMEN01C) TO CDEMO-TO-PROGRAM; XCTL PROGRAM(CDEMO-TO-PROGRAM)
    # plus CALL 'CSUTLDTC'. The commarea MOVE CDEMO-FROM-PROGRAM must NOT add edges.
    e = edges("COACTUPC")
    assert "COMEN01C" in e
    assert "CSUTLDTC" in e


def test_cbtrn03c_ddname_identity_bug2():
    # CBTRN03C ASSIGNs to TRANFILE/CARDXREF — must NOT share ddnames with
    # CBACT04C (TRANSACT/XREFFILE), so they are not coupled.
    assert ddnames("CBTRN03C") == {
        "TRANFILE", "CARDXREF", "TRANTYPE", "TRANCATG", "TRANREPT", "DATEPARM",
    }


def test_cbtrn03c_not_coupled_to_cbact04c():
    file_map = {p: ddnames(p) for p in ["CBTRN03C", "CBACT04C", "CBTRN01C", "CBTRN02C"]}
    full = {p: set() for p in CORPUS}
    full.update(file_map)
    coupling = E.data_coupling({p: sorted(full[p]) for p in CORPUS}, {p: [] for p in CORPUS}, CORPUS)
    assert "CBACT04C" not in coupling["CBTRN03C"]
    # but it DOES couple to the other TRANFILE accessors
    assert "CBTRN01C" in coupling["CBTRN03C"]
    assert "CBTRN02C" in coupling["CBTRN03C"]

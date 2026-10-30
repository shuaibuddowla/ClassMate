"use client";

import { useEffect, useState, useRef, useMemo, useCallback } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import "./transit.css";
import { REAL_STREET_POLYLINE } from "./transit-route-data";
import {
  Bus,
  Layers,
  MapPin,
  Compass,
  Radio,
  Bell,
  ChevronUp,
  ChevronDown,
  ChevronRight,
  Navigation,
  Play,
  Sparkles,
  LocateFixed,
  CheckCircle2,
  X,
  Plus,
  Minus,
  Search,
  ArrowLeft,
  List,
  Map as MapIcon,
  RefreshCw,
  SlidersHorizontal,
  Clock,
  Check
} from "lucide-react";

/* ==========================================================================
   Types & Verified MBSTU Data
   ========================================================================== */

export interface MbstuBusData {
  id: string;
  nameBn: string;
  nameEn: string;
  lat: number;
  lon: number;
  speed: number;
  heading: number;
  hasDirection?: boolean;
  routeName: string;
  status: string;
  color: string;
  isDriving: boolean;
  lastUpdated?: string;
  positionAgeSeconds?: number | null;
  isStale?: boolean;
  isEstimated?: boolean;
}

export interface CorridorStation {
  id: string;
  name: string;
  nameBn: string;
  lat: number;
  lon: number;
  order: number;
  isCampus: boolean;
}

export const CORRIDOR_MILESTONES_KM: number[] = [
  0.00,  // 1: Mukto Mancho Bus Stop
  0.23,  // 2: Academic Building-1 Bus Stop
  0.38,  // 3: SOH Hall Bus Stop
  0.46,  // 4: Mannan Hall Bus Stop
  0.65,  // 5: Academic Building-2 Bus Stop
  0.82,  // 6: Fifth Gate Bus Stop
  0.95,  // 7: Prottoi-71 Bus Stop
  1.10,  // 8: First Gate Bus Stop
  1.55,  // 9: Arif Nagar Bus Stop
  1.95,  // 10: Shena Nagar Bus Stop
  2.15,  // 11: Kagmari More Bus Stop
  2.55,  // 12: Kagmari Bridge-1 Bus Stop
  2.98,  // 13: Kagmari Bridge-2 Bus Stop
  3.32,  // 14: Baby Stand Bus Stop
  3.68,  // 15: Shantikunja More Bus Stop
  4.45,  // 16: Nirala More Bus Stop
  4.80,  // 17: Boro Kalibari More Bus Stop
  5.15,  // 18: Puraton Bus Stand Bus Stop
  5.30,  // 19: Registry Para Road Bus Stop
  5.75,  // 20: Kumudini College Gate Bus Stop
  6.00,  // 21: C & B More Bus Stop
  6.15,  // 22: Baitun Nur Jame Mosque Mor
  6.40,  // 23: Kazi-Nazrul Sarani Bus Stop
  6.50,  // 24: District Bus Stop
  6.65,  // 25: DC Lake Bus Stop
  7.15,  // 26: Bottola Road Bus Stop
  7.75,  // 27: Taltola-Shimultoli Road Bus Stop
  7.95   // 28: Pouro Uddan Bus Stop
];

export const CORRIDOR_STATIONS: CorridorStation[] = [
  { id: "mukto_mancho", name: "Mukto Mancho Bus Stop", nameBn: "মুক্তমঞ্চ বাস স্টপ", lat: 24.23512, lon: 89.89025, order: 1, isCampus: true },
  { id: "ab1", name: "Academic Building-1 Bus Stop", nameBn: "একাডেমিক ভবন-১ বাস স্টপ", lat: 24.23715, lon: 89.89038, order: 2, isCampus: true },
  { id: "soh_hall", name: "SOH Hall Bus Stop", nameBn: "এসওএইচ হল বাস স্টপ", lat: 24.23835, lon: 89.89042, order: 3, isCampus: true },
  { id: "mannan_hall", name: "Mannan Hall Bus Stop", nameBn: "মান্নান হল বাস স্টপ", lat: 24.23855, lon: 89.89105, order: 4, isCampus: true },
  { id: "ab2", name: "Academic Building-2 Bus Stop", nameBn: "একাডেমিক ভবন-২ বাস স্টপ", lat: 24.23712, lon: 89.89152, order: 5, isCampus: true },
  { id: "fifth_gate", name: "Fifth Gate Bus Stop", nameBn: "পঞ্চম গেট বাস স্টপ", lat: 24.23578, lon: 89.89178, order: 6, isCampus: true },
  { id: "prottoi_71", name: "Prottoi-71 Bus Stop", nameBn: "প্রত্যয়-৭১ বাস স্টপ", lat: 24.23482, lon: 89.89195, order: 7, isCampus: true },
  { id: "first_gate", name: "First Gate Bus Stop", nameBn: "প্রথম গেট বাস স্টপ", lat: 24.23363, lon: 89.89218, order: 8, isCampus: true },
  { id: "arif_nagar", name: "Arif Nagar Bus Stop", nameBn: "আরিফ নগর বাস স্টপ", lat: 24.23398, lon: 89.89635, order: 9, isCampus: false },
  { id: "shena_nagar", name: "Shena Nagar Bus Stop", nameBn: "সেনা নগর বাস স্টপ", lat: 24.23404, lon: 89.89991, order: 10, isCampus: false },
  { id: "kagmari_more", name: "Kagmari More Bus Stop", nameBn: "কাগমারী মোড় বাস স্টপ", lat: 24.23488, lon: 89.90125, order: 11, isCampus: false },
  { id: "kagmari_bridge_1", name: "Kagmari Bridge-1 Bus Stop", nameBn: "কাগমারী ব্রিজ-১ বাস স্টপ", lat: 24.23800, lon: 89.90250, order: 12, isCampus: false },
  { id: "kagmari_bridge_2", name: "Kagmari Bridge-2 Bus Stop", nameBn: "কাগমারী ব্রিজ-২ বাস স্টপ", lat: 24.24155, lon: 89.90426, order: 13, isCampus: false },
  { id: "baby_stand", name: "Baby Stand Bus Stop", nameBn: "বেবি স্ট্যান্ড বাস স্টপ", lat: 24.24415, lon: 89.90445, order: 14, isCampus: false },
  { id: "shantikunja", name: "Shantikunja More Bus Stop", nameBn: "শান্তিকুঞ্জ মোড় বাস স্টপ", lat: 24.24665, lon: 89.90735, order: 15, isCampus: false },
  { id: "nirala_more", name: "Nirala More Bus Stop", nameBn: "নিরালা মোড় বাস স্টপ", lat: 24.24985, lon: 89.91485, order: 16, isCampus: false },
  { id: "boro_kalibari", name: "Boro Kalibari More Bus Stop", nameBn: "বড় কালীবাড়ি মোড় বাস স্টপ", lat: 24.25135, lon: 89.91665, order: 17, isCampus: false },
  { id: "puraton_stand", name: "Puraton Bus Stand Bus Stop", nameBn: "পুরাতন বাস স্ট্যান্ড বাস স্টপ", lat: 24.25245, lon: 89.91955, order: 18, isCampus: false },
  { id: "registry_para", name: "Registry Para Road Bus Stop", nameBn: "রেজিস্ট্রি পাড়া রোড বাস স্টপ", lat: 24.25355, lon: 89.91965, order: 19, isCampus: false },
  { id: "kumudini_gate", name: "Kumudini College Gate Bus Stop", nameBn: "কুমুদিনী কলেজ গেট বাস স্টপ", lat: 24.25715, lon: 89.91885, order: 20, isCampus: false },
  { id: "cnb_more", name: "C & B More Bus Stop", nameBn: "সি অ্যান্ড বি মোড় বাস স্টপ", lat: 24.25895, lon: 89.91765, order: 21, isCampus: false },
  { id: "baitun_nur", name: "Baitun Nur Jame Mosque Mor", nameBn: "বায়তুন নূর জামে মসজিদ মোড়", lat: 24.25985, lon: 89.91715, order: 22, isCampus: false },
  { id: "kazi_nazrul", name: "Kazi-Nazrul Sarani Bus Stop", nameBn: "কাজী নজরুল সরণি বাস স্টপ", lat: 24.26175, lon: 89.91695, order: 23, isCampus: false },
  { id: "district_stop", name: "District Bus Stop", nameBn: "ডিস্ট্রিক্ট বাস স্টপ", lat: 24.26195, lon: 89.91635, order: 24, isCampus: false },
  { id: "dc_lake", name: "DC Lake Bus Stop", nameBn: "ডিসি লেক বাস স্টপ", lat: 24.26245, lon: 89.91545, order: 25, isCampus: false },
  { id: "bottola", name: "Bottola Road Bus Stop", nameBn: "বটতলা রোড বাস স্টপ", lat: 24.25845, lon: 89.91395, order: 26, isCampus: false },
  { id: "taltola", name: "Taltola-Shimultoli Road Bus Stop", nameBn: "তালতলা-শিমুলতলী রোড স্টপ", lat: 24.25285, lon: 89.91415, order: 27, isCampus: false },
  { id: "pouro_uddan", name: "Pouro Uddan Bus Stop", nameBn: "পৌর উদ্যান বাস স্টপ", lat: 24.25125, lon: 89.91445, order: 28, isCampus: false }
];

const KNOWN_BUS_INFO: Record<string, { nameBn: string; nameEn: string; color: string; defaultRoute: string }> = {
  SB0001: { nameBn: "গীতাঞ্জলি", nameEn: "Gitanjali", color: "#6366f1", defaultRoute: "Campus ⇄ Nirala (City Center)" },
  SB0002: { nameBn: "অগ্নিবীণা", nameEn: "Agnibina", color: "#f97316", defaultRoute: "Campus ⇄ Nirala (City Center)" },
  SB0003: { nameBn: "সোনারতরী", nameEn: "Sonartori", color: "#0284c7", defaultRoute: "Campus ⇄ Nirala (City Center)" },
  SB0005: { nameBn: "ধলেশ্বরী", nameEn: "Dhaleshwari", color: "#10b981", defaultRoute: "Campus ⇄ Tangail Old Bus Stand" },
  SB0009: { nameBn: "বনলতা", nameEn: "Banolata", color: "#059669", defaultRoute: "Campus ⇄ Kagmari ⇄ Nirala" },
  SB0010: { nameBn: "রক্তকরবী", nameEn: "Raktakarabi", color: "#ec4899", defaultRoute: "MBSTU Transport Corridor" }
};

export const INITIAL_FLEET: MbstuBusData[] = [
  {
    id: "SB0003",
    nameBn: "সোনারতরী",
    nameEn: "Sonartori",
    lat: 24.23482,
    lon: 89.89195,
    speed: 0,
    heading: 181,
    routeName: "Campus ⇄ Nirala (City Center)",
    status: "Parked at Campus Standby",
    color: "#0284c7",
    isDriving: false
  },
  {
    id: "SB0002",
    nameBn: "অগ্নিবীণা",
    nameEn: "Agnibina",
    lat: 24.2515,
    lon: 89.9165,
    speed: 18,
    heading: 260,
    routeName: "Campus ⇄ Nirala (City Center)",
    status: "In transit (18 km/h)",
    color: "#f97316",
    isDriving: true
  },
  {
    id: "SB0010",
    nameBn: "রক্তকরবী",
    nameEn: "Raktakarabi",
    lat: 24.2350,
    lon: 89.8919,
    speed: 0,
    heading: 0,
    routeName: "MBSTU Transport Corridor",
    status: "Parked at Campus Standby",
    color: "#ec4899",
    isDriving: false
  }
];

export function getDistanceMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371e3;
  const phi1 = (lat1 * Math.PI) / 180;
  const phi2 = (lat2 * Math.PI) / 180;
  const deltaPhi = ((lat2 - lat1) * Math.PI) / 180;
  const deltaLambda = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
    Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

export function findClosestPolylineIndex(point: [number, number], polyline: [number, number][]): number {
  let closestIndex = 0;
  let minDistance = Infinity;
  for (let i = 0; i < polyline.length; i++) {
    const dist = getDistanceMeters(point[0], point[1], polyline[i][0], polyline[i][1]);
    if (dist < minDistance) {
      minDistance = dist;
      closestIndex = i;
    }
  }
  return closestIndex;
}

function normalizeBusHeading(heading: unknown, fallback = 0): { heading: number; hasDirection: boolean } {
  if (typeof heading === "number" && !Number.isNaN(heading)) {
    const norm = ((heading % 360) + 360) % 360;
    return { heading: norm, hasDirection: true };
  }
  if (typeof heading === "string") {
    const parsed = parseFloat(heading);
    if (!Number.isNaN(parsed)) {
      const norm = ((parsed % 360) + 360) % 360;
      return { heading: norm, hasDirection: true };
    }
  }
  return { heading: fallback, hasDirection: false };
}

function parseServerTimestamp(raw: unknown): number | null {
  if (!raw) return null;
  if (typeof raw === "number") {
    return raw > 1e11 ? raw : raw * 1000;
  }
  if (typeof raw === "string") {
    const t = Date.parse(raw);
    if (!Number.isNaN(t)) return t;
    const num = Number(raw);
    if (!Number.isNaN(num)) return num > 1e11 ? num : num * 1000;
  }
  return null;
}

function formatBusShortId(busId: string): string {
  const digits = busId.replace(/^SB0*/i, "");
  return digits ? digits.padStart(4, "0") : busId;
}

function predictBusPosition(bus: MbstuBusData, nowMs: number): MbstuBusData {
  if (!bus.lastUpdated) {
    return { ...bus, positionAgeSeconds: null, isStale: false, isEstimated: false };
  }
  const lastMs = Date.parse(bus.lastUpdated);
  if (Number.isNaN(lastMs)) {
    return { ...bus, positionAgeSeconds: null, isStale: false, isEstimated: false };
  }
  const ageSeconds = Math.max(0, Math.floor((nowMs - lastMs) / 1000));
  const isStale = ageSeconds > 180;

  if (bus.speed <= 2 || ageSeconds <= 3 || isStale) {
    return { ...bus, positionAgeSeconds: ageSeconds, isStale, isEstimated: false };
  }

  const travelSeconds = Math.min(ageSeconds, 45);
  const travelMeters = (bus.speed * 1000 / 3600) * travelSeconds;
  const currentIdx = findClosestPolylineIndex([bus.lat, bus.lon], REAL_STREET_POLYLINE);
  let accumulated = 0;
  let nextIdx = currentIdx;
  let guard = 0;

  while (accumulated < travelMeters && guard < REAL_STREET_POLYLINE.length) {
    const stepDist = getDistanceMeters(
      REAL_STREET_POLYLINE[nextIdx][0],
      REAL_STREET_POLYLINE[nextIdx][1],
      REAL_STREET_POLYLINE[(nextIdx + 1) % REAL_STREET_POLYLINE.length][0],
      REAL_STREET_POLYLINE[(nextIdx + 1) % REAL_STREET_POLYLINE.length][1]
    );
    accumulated += stepDist;
    nextIdx = (nextIdx + 1) % REAL_STREET_POLYLINE.length;
    guard++;
  }

  const [predLat, predLon] = REAL_STREET_POLYLINE[nextIdx];
  return {
    ...bus,
    lat: predLat,
    lon: predLon,
    positionAgeSeconds: ageSeconds,
    isStale,
    isEstimated: true
  };
}

function playArrivalChime() {
  try {
    const ctx = new (window.AudioContext || (window as any).webkitAudioContext)();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = "sine";
    osc.frequency.setValueAtTime(587.33, ctx.currentTime);
    osc.frequency.exponentialRampToValueAtTime(880, ctx.currentTime + 0.15);
    gain.gain.setValueAtTime(0.2, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.4);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.4);
  } catch (e) {}
}

/* ==========================================================================
   Main Transit Tracker Component
   ========================================================================== */

export function Transit({ ctx }: { ctx?: any }) {
  // Navigation & View Mode: "list" (Image 1) | "detail" (Image 2) | "map" (Live Google Maps)
  const [activeView, setActiveView] = useState<"list" | "detail" | "map">("list");
  const [detailBusId, setDetailBusId] = useState<string | null>(null);
  const [listSearch, setListSearch] = useState<string>("");
  const [listFilter, setListFilter] = useState<"all" | "driving" | "standby" | "to_campus" | "to_city">("all");
  const [selectedStoppageAction, setSelectedStoppageAction] = useState<CorridorStation | null>(null);

  // Live Fleet State
  const [fleet, setFleet] = useState<MbstuBusData[]>([]);
  const [selectedBusId, setSelectedBusId] = useState<string>("");
  const [satelliteView, setSatelliteView] = useState<boolean>(true);
  const [sheetExpanded, setSheetExpanded] = useState<boolean>(false);
  const [isDemoMode, setIsDemoMode] = useState<boolean>(false);
  const [wsStatus, setWsStatus] = useState<"connecting" | "connected" | "disconnected">("connecting");
  const [clockNow, setClockNow] = useState<number>(() => Date.now());
  const [alertedStops, setAlertedStops] = useState<Record<string, boolean>>({});
  const [arrivalNotice, setArrivalNotice] = useState<{ busName: string; stopName: string } | null>(null);
  const [stopFilter, setStopFilter] = useState<string>("");
  const [isBusOffscreen, setIsBusOffscreen] = useState<boolean>(false);

  // Map DOM & Leaflet Refs
  const mapContainerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const tileLayerRef = useRef<L.TileLayer | null>(null);
  const polylineRef = useRef<L.Polyline | null>(null);
  const polylineCasingRef = useRef<L.Polyline | null>(null);
  const approachingPolylineRef = useRef<L.Polyline | null>(null);
  const approachingGlowRef = useRef<L.Polyline | null>(null);
  const busMarkersRef = useRef<Map<string, L.Marker>>(new Map());
  const stopMarkersRef = useRef<Map<string, L.Marker>>(new Map());
  const campusCircleRef = useRef<L.Circle | null>(null);
  const demoBusInjectedRef = useRef(false);

  // User Geolocation State (Real-time device tracking)
  const [userLocation, setUserLocation] = useState<{ lat: number; lon: number } | null>(null);
  const [locPermission, setLocPermission] = useState<"prompt" | "granted" | "denied">("prompt");
  const userMarkerRef = useRef<L.Marker | null>(null);

  // Update clock every second for live ETA and freshness calculations
  useEffect(() => {
    const timer = setInterval(() => setClockNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  // Request & watch user geolocation
  const requestLocation = useCallback(() => {
    if (typeof window === "undefined" || !("geolocation" in navigator)) return;
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setUserLocation({
          lat: pos.coords.latitude,
          lon: pos.coords.longitude
        });
        setLocPermission("granted");
        if (mapRef.current && activeView === "map") {
          mapRef.current.flyTo([pos.coords.latitude, pos.coords.longitude], 16, { animate: true, duration: 0.8 });
        }
      },
      () => setLocPermission("denied"),
      { enableHighAccuracy: true, timeout: 8000 }
    );
  }, [activeView]);

  useEffect(() => {
    if (typeof window === "undefined" || !("geolocation" in navigator)) return;
    try {
      // Purge old mock fleet from localStorage so only authentic live buses are shown
      localStorage.removeItem("classmate_mbstu_fleet");
    } catch (e) {}

    const watchId = navigator.geolocation.watchPosition(
      (pos) => {
        setUserLocation({
          lat: pos.coords.latitude,
          lon: pos.coords.longitude
        });
        setLocPermission("granted");
      },
      (err) => {
        if (err.code === err.PERMISSION_DENIED) {
          setLocPermission("denied");
        }
      },
      { enableHighAccuracy: true, maximumAge: 10000, timeout: 10000 }
    );

    return () => {
      navigator.geolocation.clearWatch(watchId);
    };
  }, []);

  const displayFleet = useMemo(
    () => fleet.map((bus) => predictBusPosition(bus, clockNow)),
    [fleet, clockNow]
  );
  const freshFleetCount = displayFleet.filter((bus) => bus.positionAgeSeconds !== null && !bus.isStale).length;

  // Active Bus (ONLY when explicitly selected/tapped by the user)
  const selectedBus = useMemo(
    () => (selectedBusId ? displayFleet.find((b) => b.id === selectedBusId) || null : null),
    [displayFleet, selectedBusId]
  );

  // Detailed Bus for Detail View
  const detailBus = useMemo(
    () => displayFleet.find((b) => b.id === detailBusId) || selectedBus || displayFleet[0] || null,
    [displayFleet, detailBusId, selectedBus]
  );

  // Map stations to polyline indices for accurate sequential corridor tracking
  const stationPolyIndices = useMemo(() => {
    return CORRIDOR_STATIONS.map((st) => ({
      station: st,
      idx: findClosestPolylineIndex([st.lat, st.lon], REAL_STREET_POLYLINE)
    }));
  }, []);

  // Helper to find next stop and ETA for ANY bus
  const getNextStopForBus = useCallback((bus: MbstuBusData) => {
    const busIdx = findClosestPolylineIndex([bus.lat, bus.lon], REAL_STREET_POLYLINE);
    let targetCandidate = stationPolyIndices[0];
    let minForwardSteps = Infinity;

    for (const item of stationPolyIndices) {
      let forwardSteps = item.idx - busIdx;
      if (forwardSteps < 0) {
        forwardSteps += REAL_STREET_POLYLINE.length;
      }
      const directDist = getDistanceMeters(bus.lat, bus.lon, item.station.lat, item.station.lon);
      if (directDist < 40) {
        forwardSteps += REAL_STREET_POLYLINE.length;
      }
      if (forwardSteps > 0 && forwardSteps < minForwardSteps) {
        minForwardSteps = forwardSteps;
        targetCandidate = item;
      }
    }

    const nextStation = targetCandidate.station;
    const distanceMeters = getDistanceMeters(bus.lat, bus.lon, nextStation.lat, nextStation.lon);
    const distanceKm = (distanceMeters / 1000).toFixed(2);
    const effectiveSpeed = Math.max(bus.speed, 25);
    const etaMinutes = Math.max(1, Math.round((distanceMeters / 1000 / effectiveSpeed) * 60));

    return {
      station: nextStation,
      distanceKm,
      etaMinutes
    };
  }, [stationPolyIndices]);

  // Route Progress percentage helper
  const getBusProgress = useCallback((bus: MbstuBusData) => {
    const busIdx = findClosestPolylineIndex([bus.lat, bus.lon], REAL_STREET_POLYLINE);
    return Math.min(96, Math.max(8, Math.round(((busIdx + 1) / REAL_STREET_POLYLINE.length) * 100)));
  }, []);

  // Filtered Fleet for List View (Image 1)
  const filteredFleet = useMemo(() => {
    let result = displayFleet;

    if (listFilter === "driving") {
      result = result.filter((b) => b.speed > 0);
    } else if (listFilter === "standby") {
      result = result.filter((b) => b.speed === 0);
    } else if (listFilter === "to_campus") {
      result = result.filter((b) => {
        const next = getNextStopForBus(b);
        return next.station.isCampus;
      });
    } else if (listFilter === "to_city") {
      result = result.filter((b) => {
        const next = getNextStopForBus(b);
        return !next.station.isCampus;
      });
    }

    if (listSearch.trim()) {
      const q = listSearch.toLowerCase().trim();
      result = result.filter(
        (b) =>
          b.nameBn.includes(q) ||
          b.nameEn.toLowerCase().includes(q) ||
          b.id.toLowerCase().includes(q) ||
          b.routeName.toLowerCase().includes(q)
      );
    }

    return result;
  }, [displayFleet, listFilter, listSearch, getNextStopForBus]);

  // Compute Next Approaching Stop for the currently selected bus (Map & Details)
  const nextApproachingStopInfo = useMemo(() => {
    if (!selectedBus) return null;

    const busIdx = findClosestPolylineIndex([selectedBus.lat, selectedBus.lon], REAL_STREET_POLYLINE);
    let targetCandidate = stationPolyIndices[0];
    let minForwardSteps = Infinity;

    for (const item of stationPolyIndices) {
      let forwardSteps = item.idx - busIdx;
      if (forwardSteps < 0) {
        forwardSteps += REAL_STREET_POLYLINE.length;
      }
      const directDist = getDistanceMeters(selectedBus.lat, selectedBus.lon, item.station.lat, item.station.lon);
      if (directDist < 40) {
        forwardSteps += REAL_STREET_POLYLINE.length;
      }
      if (forwardSteps > 0 && forwardSteps < minForwardSteps) {
        minForwardSteps = forwardSteps;
        targetCandidate = item;
      }
    }

    const nextStation = targetCandidate.station;
    const pathSegment: [number, number][] = [[selectedBus.lat, selectedBus.lon]];
    let curr = busIdx;
    const dest = targetCandidate.idx;
    let guard = 0;
    while (curr !== dest && guard < REAL_STREET_POLYLINE.length) {
      curr = (curr + 1) % REAL_STREET_POLYLINE.length;
      pathSegment.push(REAL_STREET_POLYLINE[curr]);
      guard++;
    }
    pathSegment.push([nextStation.lat, nextStation.lon]);

    const distanceMeters = pathSegment.slice(1).reduce((total, point, index) => {
      const previous = pathSegment[index];
      return total + getDistanceMeters(previous[0], previous[1], point[0], point[1]);
    }, 0);
    const distanceKm = (distanceMeters / 1000).toFixed(2);
    const isStandby = selectedBus.speed === 0;
    const effectiveSpeed = Math.max(selectedBus.speed, 25);
    const etaMinutes = Math.max(1, Math.round((distanceMeters / 1000 / effectiveSpeed) * 60));

    return {
      station: nextStation,
      distanceMeters,
      distanceKm,
      etaMinutes,
      isStandby,
      pathSegment
    };
  }, [selectedBus, stationPolyIndices]);

  // Toggle Alert for a Stop
  const toggleStopAlert = useCallback((stopId: string) => {
    setAlertedStops((prev) => {
      const next = !prev[stopId];
      if (next) {
        playArrivalChime();
      }
      return {
        ...prev,
        [stopId]: next
      };
    });
  }, []);

  // Switch to Map with Smooth Auto-Focus
  const switchToMap = useCallback((busId?: string) => {
    if (busId) {
      setSelectedBusId(busId);
      setDetailBusId(busId);
    }
    setActiveView("map");
    setTimeout(() => {
      if (mapRef.current) {
        mapRef.current.invalidateSize();
        const target = (busId ? displayFleet.find((b) => b.id === busId) : selectedBus) || displayFleet[0];
        if (target) {
          mapRef.current.flyTo([target.lat, target.lon], 17, { animate: true, duration: 0.8 });
        }
      }
    }, 150);
  }, [displayFleet, selectedBus]);

  const toggleDemoMode = useCallback(() => {
    if (!isDemoMode) {
      const existingDemoBus = fleet.some((bus) => bus.id === "SB0003");
      if (!existingDemoBus) {
        const template = INITIAL_FLEET.find((bus) => bus.id === "SB0003") || INITIAL_FLEET[0];
        const [lat, lon] = REAL_STREET_POLYLINE[0];
        setFleet((current) => [
          ...current,
          {
            ...template,
            id: "SB0003",
            lat,
            lon,
            speed: 32,
            heading: 180,
            hasDirection: true,
            isDriving: true,
            lastUpdated: new Date().toISOString(),
            status: "Road Demo (32 km/h)"
          }
        ]);
        setSelectedBusId("SB0003");
        setDetailBusId("SB0003");
        demoBusInjectedRef.current = true;
      }
      setIsDemoMode(true);
      return;
    }

    setIsDemoMode(false);
    if (demoBusInjectedRef.current) {
      setFleet((current) => current.filter((bus) => bus.id !== "SB0003"));
      setSelectedBusId((current) => (current === "SB0003" ? "" : current));
      setDetailBusId((current) => (current === "SB0003" ? null : current));
      demoBusInjectedRef.current = false;
    }
  }, [fleet, isDemoMode]);

  // Center Selected Bus on Map
  const centerSelectedBus = useCallback(() => {
    if (selectedBus && mapRef.current) {
      mapRef.current.flyTo([selectedBus.lat, selectedBus.lon], 17, { animate: true, duration: 0.8 });
    }
  }, [selectedBus]);

  // Check if bus is offscreen
  const checkBusInBounds = useCallback(() => {
    if (!mapRef.current || !selectedBus) {
      setIsBusOffscreen(false);
      return;
    }
    const bounds = mapRef.current.getBounds();
    const isIn = bounds.contains([selectedBus.lat, selectedBus.lon]);
    setIsBusOffscreen(!isIn);
  }, [selectedBus]);

  useEffect(() => {
    if (!mapRef.current) return;
    const map = mapRef.current;
    map.on("moveend", checkBusInBounds);
    map.on("zoomend", checkBusInBounds);
    checkBusInBounds();
    return () => {
      map.off("moveend", checkBusInBounds);
      map.off("zoomend", checkBusInBounds);
    };
  }, [checkBusInBounds]);

  // Fly to campus
  const recenterCampus = useCallback(() => {
    if (mapRef.current) {
      mapRef.current.flyTo([24.2355, 89.8916], 16.5, { animate: true, duration: 0.8 });
    }
  }, []);

  // Arrival Alert Trigger
  useEffect(() => {
    if (!selectedBus || selectedBus.isStale) return;
    const reachedStop = CORRIDOR_STATIONS.find(
      (station) =>
        alertedStops[station.id] &&
        getDistanceMeters(selectedBus.lat, selectedBus.lon, station.lat, station.lon) <= 220
    );
    if (!reachedStop) return;
    setAlertedStops((prev) => ({ ...prev, [reachedStop.id]: false }));
    setArrivalNotice({ busName: selectedBus.nameBn, stopName: reachedStop.name });
    playArrivalChime();
    if (typeof navigator !== "undefined" && navigator.vibrate) {
      navigator.vibrate([200, 100, 200]);
    }
  }, [selectedBus, alertedStops]);

  // 1. Initialize Map
  useEffect(() => {
    if (!mapContainerRef.current || mapRef.current) return;

    const map = L.map(mapContainerRef.current, {
      center: [24.2365, 89.8914],
      zoom: 17,
      zoomControl: false,
      attributionControl: false
    });
    mapRef.current = map;

    map.on("click", (e) => {
      const target = (e.originalEvent?.target as HTMLElement | null);
      if (
        !target?.closest(".transit-bus-pin-wrapper") &&
        !target?.closest(".transit-lollipop-wrapper") &&
        !target?.closest(".transit-bottom-sheet") &&
        !target?.closest(".transit-top-overlay")
      ) {
        setSelectedBusId("");
      }
    });

    setTimeout(() => map.invalidateSize(), 200);
    const ro = new ResizeObserver(() => map.invalidateSize());
    if (mapContainerRef.current) ro.observe(mapContainerRef.current);

    return () => {
      ro.disconnect();
      map.remove();
      mapRef.current = null;
    };
  }, []);

  // 2. Tile Layer (Satellite vs Street)
  useEffect(() => {
    if (!mapRef.current) return;
    if (tileLayerRef.current) {
      mapRef.current.removeLayer(tileLayerRef.current);
    }

    const tileUrl = satelliteView
      ? "https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}"
      : "https://mt{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}";

    const layer = L.tileLayer(tileUrl, {
      subdomains: ["0", "1", "2", "3"],
      maxZoom: 21,
      maxNativeZoom: 20
    });
    layer.addTo(mapRef.current);
    tileLayerRef.current = layer;
  }, [satelliteView]);

  // 3. Render Street Polyline & Campus Circle
  useEffect(() => {
    if (!mapRef.current) return;
    const map = mapRef.current;

    if (!polylineCasingRef.current) {
      polylineCasingRef.current = L.polyline(REAL_STREET_POLYLINE, {
        color: "#ffffff",
        weight: 9,
        opacity: 0.92,
        lineCap: "round",
        lineJoin: "round"
      }).addTo(map);
    }
    if (!polylineRef.current) {
      polylineRef.current = L.polyline(REAL_STREET_POLYLINE, {
        color: "#bd1556",
        weight: 5,
        opacity: 0.95,
        lineCap: "round",
        lineJoin: "round"
      }).addTo(map);
    }
    if (!campusCircleRef.current) {
      campusCircleRef.current = L.circle([24.23488, 89.89155], {
        radius: 58,
        color: "#bd1556",
        weight: 1.5,
        fillColor: "#e83e7c",
        fillOpacity: 0.18,
        dashArray: "4, 4"
      }).addTo(map);
    }
  }, []);

  // 4. Render Dynamic Approaching Road Highlight
  useEffect(() => {
    if (!mapRef.current) return;
    const map = mapRef.current;

    if (approachingGlowRef.current) {
      map.removeLayer(approachingGlowRef.current);
      approachingGlowRef.current = null;
    }
    if (approachingPolylineRef.current) {
      map.removeLayer(approachingPolylineRef.current);
      approachingPolylineRef.current = null;
    }

    if (!nextApproachingStopInfo || !selectedBus) return;

    approachingGlowRef.current = L.polyline(nextApproachingStopInfo.pathSegment, {
      color: "#00f2fe",
      weight: 12,
      opacity: 0.38,
      lineCap: "round",
      lineJoin: "round"
    }).addTo(map);

    approachingPolylineRef.current = L.polyline(nextApproachingStopInfo.pathSegment, {
      color: "#38bdf8",
      weight: 5,
      opacity: 0.98,
      dashArray: "8, 6",
      className: "transit-animated-approaching-path",
      lineCap: "round",
      lineJoin: "round"
    }).addTo(map);
  }, [nextApproachingStopInfo, selectedBus]);

  // 5. Render 28 Red Lollipop Bus Stop Sign Markers
  useEffect(() => {
    if (!mapRef.current) return;
    const map = mapRef.current;

    CORRIDOR_STATIONS.forEach((station) => {
      const isApproaching = nextApproachingStopInfo?.station.id === station.id;
      const isAlertArmed = alertedStops[station.id];

      const html = `
        <div class="transit-lollipop-wrapper ${isApproaching ? "approaching" : ""}">
          ${isApproaching ? `<div class="transit-approaching-pulse-ring"></div>` : ""}
          <div class="transit-lollipop-sign ${isAlertArmed ? "alert-armed" : ""}">
            <span class="transit-lollipop-bus-text">${isAlertArmed ? "🔔" : "BUS"}</span>
          </div>
          <div class="transit-lollipop-stem"></div>
          <div class="transit-lollipop-base"></div>
          <div class="transit-lollipop-label">${station.nameBn}</div>
          ${
            isApproaching && nextApproachingStopInfo
              ? `<div class="transit-next-stop-badge">
                  <span class="transit-next-stop-dot"></span>
                  <span>NEXT • ${
                    nextApproachingStopInfo.isStandby
                      ? `${nextApproachingStopInfo.distanceKm} km`
                      : `${nextApproachingStopInfo.etaMinutes} min`
                  }</span>
                </div>`
              : ""
          }
        </div>
      `;

      const icon = L.divIcon({
        className: "transit-lollipop-icon-container",
        html,
        iconSize: [120, 56],
        iconAnchor: [60, 29]
      });

      let marker = stopMarkersRef.current.get(station.id);
      if (!marker) {
        marker = L.marker([station.lat, station.lon], { icon, zIndexOffset: 200 }).addTo(map);
        marker.on("click", (e) => {
          L.DomEvent.stopPropagation(e);
          setSelectedStoppageAction(station);
        });
        stopMarkersRef.current.set(station.id, marker);
      } else {
        marker.setIcon(icon);
        marker.setZIndexOffset(isApproaching ? 500 : 200);
      }
    });
  }, [nextApproachingStopInfo, alertedStops]);

  // 6. Render Active Bus Markers
  useEffect(() => {
    if (!mapRef.current) return;
    const map = mapRef.current;
    const currentBusIds = new Set(displayFleet.map((b) => b.id));

    busMarkersRef.current.forEach((marker, id) => {
      if (!currentBusIds.has(id)) {
        map.removeLayer(marker);
        busMarkersRef.current.delete(id);
      }
    });

    displayFleet.forEach((bus) => {
      const isSelected = bus.id === selectedBusId;

      const html = `
        <div class="transit-bus-pin-wrapper ${isSelected ? "selected" : ""}" style="--bus-color: ${bus.color};">
          ${
            isSelected
              ? `<div class="transit-bus-callout visible">
                  <div class="transit-callout-top">
                    <span class="transit-callout-dot" style="background: ${bus.color};"></span>
                    <span class="transit-callout-name">${bus.nameBn}</span>
                    <span class="transit-callout-id">${bus.id}</span>
                  </div>
                  <div class="transit-callout-bottom">
                    <span class="transit-callout-speed ${bus.isDriving ? "speed-active" : "speed-standby"}">
                      ${bus.isStale ? "GPS বিলম্বিত" : bus.isDriving ? `⚡ ${Math.round(bus.speed)} km/h` : "🅿️ স্ট্যান্ডবাই"}
                    </span>
                  </div>
                  <div class="transit-callout-arrow"></div>
                </div>`
              : ""
          }
          ${bus.isDriving ? `<div class="transit-bus-radar-ring" style="border-color: ${bus.color};"></div>` : ""}
          <div class="transit-bus-badge" style="border-color: ${bus.color};">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" class="transit-bus-icon-svg" xmlns="http://www.w3.org/2000/svg">
              <path d="M4 16.5C4 17.33 4.67 18 5.5 18H6V19.5C6 20.33 6.67 21 7.5 21H8.5C9.33 21 10 20.33 10 19.5V18H14V19.5C14 20.33 14.67 21 15.5 21H16.5C17.33 21 18 20.33 18 19.5V18H18.5C19.33 18 20 17.33 20 16.5V6.5C20 3.8 17.8 3 12 3C6.2 3 4 3.8 4 6.5V16.5Z" fill="${bus.color}"/>
              <path d="M5.8 7H18.2C18.4 7 18.5 7.15 18.5 7.35V11.2C18.5 11.4 18.4 11.5 18.2 11.5H5.8C5.6 11.5 5.5 11.4 5.5 11.2V7.35C5.5 7.15 5.6 7 5.8 7Z" fill="#ffffff" fill-opacity="0.95"/>
              <rect x="8.5" y="4.5" width="7" height="1.4" rx="0.7" fill="#ffffff" fill-opacity="0.9"/>
              <circle cx="7" cy="14.8" r="1.3" fill="#fef08a"/>
              <circle cx="17" cy="14.8" r="1.3" fill="#fef08a"/>
              <rect x="10" y="14.4" width="4" height="1" rx="0.5" fill="#ffffff" fill-opacity="0.8"/>
              <path d="M5.5 16.5H18.5" stroke="#ffffff" stroke-width="0.7" stroke-linecap="round" stroke-opacity="0.6"/>
            </svg>
          </div>
          <div class="transit-bus-pointer" style="border-top-color: ${bus.color};"></div>
        </div>
      `;

      const icon = L.divIcon({
        className: "transit-bus-marker-container",
        html,
        iconSize: [44, 48],
        iconAnchor: [22, 48]
      });

      let marker = busMarkersRef.current.get(bus.id);
      if (!marker) {
        marker = L.marker([bus.lat, bus.lon], { icon, zIndexOffset: isSelected ? 1000 : 800 }).addTo(map);
        marker.on("click", (e) => {
          L.DomEvent.stopPropagation(e);
          setSelectedBusId(bus.id);
          setDetailBusId(bus.id);
        });
        busMarkersRef.current.set(bus.id, marker);
      } else {
        marker.setLatLng([bus.lat, bus.lon]);
        marker.setIcon(icon);
        marker.setZIndexOffset(isSelected ? 1000 : 800);
      }
    });
  }, [displayFleet, selectedBusId]);

  // 6.1 Render Live User Geolocation Marker on Map
  useEffect(() => {
    if (!mapRef.current) return;
    const map = mapRef.current;

    if (!userLocation) {
      if (userMarkerRef.current) {
        map.removeLayer(userMarkerRef.current);
        userMarkerRef.current = null;
      }
      return;
    }

    const html = `
      <div class="transit-user-marker">
        <div class="transit-user-marker-pulse"></div>
        <div class="transit-user-marker-dot"></div>
      </div>
    `;

    const icon = L.divIcon({
      className: "transit-user-location-wrapper",
      html,
      iconSize: [26, 26],
      iconAnchor: [13, 13]
    });

    if (!userMarkerRef.current) {
      userMarkerRef.current = L.marker([userLocation.lat, userLocation.lon], {
        icon,
        zIndexOffset: 1500
      }).addTo(map);
      userMarkerRef.current.bindPopup(
        "<div style='font-weight:700; font-size:12px; text-align:center;'>📍 আপনার অবস্থান<br/><span style='font-size:10px; color:#64748b; font-weight:normal;'>You are here</span></div>",
        { offset: [0, -8] }
      );
    } else {
      userMarkerRef.current.setLatLng([userLocation.lat, userLocation.lon]);
    }
  }, [userLocation]);

  // 6.2 Auto-Adapt Map View to Active Bus Location & Forward Route Window
  const lastCameraPanRef = useRef<number>(0);
  useEffect(() => {
    if (!mapRef.current || !selectedBus || activeView !== "map") return;
    const now = Date.now();
    // Throttle auto-adaptation so user drag/zoom isn't interrupted
    if (now - lastCameraPanRef.current < 2600) return;
    lastCameraPanRef.current = now;

    const map = mapRef.current;
    const busIdx = findClosestPolylineIndex([selectedBus.lat, selectedBus.lon], REAL_STREET_POLYLINE);
    const forwardPoints: [number, number][] = [[selectedBus.lat, selectedBus.lon]];

    // Collect next 18-20 route points ahead of the bus along the road corridor
    for (let s = 1; s <= 20; s++) {
      forwardPoints.push(REAL_STREET_POLYLINE[(busIdx + s) % REAL_STREET_POLYLINE.length]);
    }

    if (nextApproachingStopInfo?.station) {
      forwardPoints.push([nextApproachingStopInfo.station.lat, nextApproachingStopInfo.station.lon]);
    }

    const bounds = L.latLngBounds(forwardPoints);
    map.fitBounds(bounds, {
      paddingTopLeft: [75, 30],
      paddingBottomRight: [50, 30],
      maxZoom: 17,
      animate: true
    });
  }, [selectedBus?.lat, selectedBus?.lon, activeView, nextApproachingStopInfo?.station]);

  // 7. Road Demo Simulation Loop
  useEffect(() => {
    if (!isDemoMode) return;

    let progressIndex = 0;
    const interval = setInterval(() => {
      progressIndex = (progressIndex + 1) % REAL_STREET_POLYLINE.length;
      const [demoLat, demoLon] = REAL_STREET_POLYLINE[progressIndex];
      const nextCoord = REAL_STREET_POLYLINE[(progressIndex + 1) % REAL_STREET_POLYLINE.length];
      const heading = (Math.atan2(nextCoord[1] - demoLon, nextCoord[0] - demoLat) * 180) / Math.PI;

      setFleet((prev) =>
        prev.map((bus) =>
          bus.id === "SB0003"
            ? {
                ...bus,
                lat: demoLat,
                lon: demoLon,
                speed: 32,
                heading: (heading + 360) % 360,
                hasDirection: true,
                status: "Road Demo (Cruising 32 km/h)",
                lastUpdated: new Date().toISOString()
              }
            : bus
        )
      );
    }, 450);

    return () => clearInterval(interval);
  }, [isDemoMode]);

  // 8. Live Socket.IO (Engine.IO v4) Connection to transport.mbstu.ac.bd
  useEffect(() => {
    if (isDemoMode) {
      setWsStatus("connected");
      return;
    }

    let isDisposed = false;
    let ws: WebSocket | null = null;
    let reconnectTimeout: any = null;

    const connect = () => {
      try {
        setWsStatus("connecting");
        // Genuine university telemetry Engine.IO v4 endpoint
        ws = new WebSocket("wss://transport.mbstu.ac.bd/socket.io/?EIO=4&transport=websocket");

        ws.onopen = () => {
          if (!isDisposed) {
            setWsStatus("connecting");
          }
        };

        ws.onmessage = (event) => {
          if (isDisposed) return;
          const msg = typeof event.data === "string" ? event.data : "";
          if (!msg) return;

          // 1. Engine.IO Handshake response
          if (msg.startsWith("0")) {
            // Send Socket.IO connect frame to default namespace "/"
            try {
              ws?.send("40");
            } catch (e) {}
            if (!isDisposed) setWsStatus("connected");
            return;
          }

          // 2. Engine.IO Heartbeat Ping from Server -> Reply with Pong ("3")
          if (msg === "2") {
            try {
              ws?.send("3");
            } catch (e) {}
            return;
          }

          // 3. Socket.IO Connected ACK
          if (msg.startsWith("40")) {
            if (!isDisposed) setWsStatus("connected");
            return;
          }

          // 4. Socket.IO Event Packet ("42[...]")
          if (msg.startsWith("42")) {
            try {
              const payload = JSON.parse(msg.slice(2));
              if (!Array.isArray(payload) || payload.length < 2) return;
              const [eventName, rawData] = payload;

              // Only process bus telemetry events
              if (
                eventName === "flutter-bus-data" ||
                eventName === "studentGpsData" ||
                eventName === "student-gps-data"
              ) {
                const list = Array.isArray(rawData) ? rawData : [rawData];
                const valid = list.filter((b: any) => {
                  if (!b) return false;
                  const lat = typeof b.lat === "number" ? b.lat : typeof b.latitude === "number" ? b.latitude : null;
                  const lon = typeof b.lon === "number" ? b.lon : typeof b.long === "number" ? b.long : typeof b.longitude === "number" ? b.longitude : null;
                  return lat !== null && lon !== null && lat > 20 && lat < 28 && lon > 85 && lon < 95;
                });

                if (valid.length > 0) {
                  setFleet((prevFleet) => {
                    const map = new Map<string, MbstuBusData>();
                    prevFleet.forEach((b) => map.set(b.id, b));

                    valid.forEach((raw: any) => {
                      const id = String(raw.id || raw.busId || raw.bus_id || raw.imei || "SB0001").toUpperCase();
                      const lat = typeof raw.lat === "number" ? raw.lat : raw.latitude;
                      const lon = typeof raw.lon === "number" ? raw.lon : (raw.long ?? raw.longitude);
                      const nameFromPacket = raw.busName || raw.nameBn || raw.name;

                      // Match known metadata or use packet info
                      let info = KNOWN_BUS_INFO[id];
                      if (!info && nameFromPacket) {
                        const matchKey = Object.keys(KNOWN_BUS_INFO).find((k) => KNOWN_BUS_INFO[k].nameBn === nameFromPacket);
                        if (matchKey) info = KNOWN_BUS_INFO[matchKey];
                      }
                      if (!info) {
                        info = {
                          nameBn: nameFromPacket || "MBSTU বাস",
                          nameEn: raw.nameEn || id,
                          color: "#0284c7",
                          defaultRoute: "MBSTU Transport Corridor"
                        };
                      }

                      const speedVal = typeof raw.speed === "number" ? Math.max(0, raw.speed) : 0;
                      const { heading, hasDirection } = normalizeBusHeading(raw.heading, 0);
                      const isDriving = raw.isDriving ?? speedVal > 0;

                      map.set(id, {
                        id,
                        nameBn: info.nameBn,
                        nameEn: info.nameEn,
                        lat,
                        lon,
                        speed: speedVal,
                        heading,
                        hasDirection,
                        routeName: raw.routeName || raw.route_name || info.defaultRoute,
                        status: isDriving ? `চলন্ত (${Math.round(speedVal)} কিমি/ঘ)` : "ক্যাম্পাস স্ট্যান্ডবাই",
                        color: info.color,
                        isDriving,
                        lastUpdated: new Date().toISOString()
                      });
                    });

                    return Array.from(map.values());
                  });
                }
              }
            } catch (err) {}
          }
        };

        ws.onclose = () => {
          if (!isDisposed) {
            setWsStatus("disconnected");
            reconnectTimeout = setTimeout(connect, 4000);
          }
        };

        ws.onerror = () => {
          if (!isDisposed) {
            setWsStatus("disconnected");
          }
        };
      } catch (err) {
        if (!isDisposed) {
          setWsStatus("disconnected");
          reconnectTimeout = setTimeout(connect, 5000);
        }
      }
    };

    connect();

    return () => {
      isDisposed = true;
      if (reconnectTimeout) clearTimeout(reconnectTimeout);
      if (ws) ws.close();
    };
  }, [isDemoMode]);

  /* =========================================================================
     VIEW 1: TRANSIT LIST VIEW (Image 1)
     ========================================================================= */
  const renderListView = () => {
    return (
      <div className="transit-list-screen">
        {/* Top Header Card */}
        <div className="transit-list-header-card">
          <div className="transit-list-header-top">
            <div className="transit-list-title-box">
              <h1 className="transit-list-main-title">MBSTU বাস লাইভ ট্র্যাকার</h1>
              <div className="transit-list-live-badge">
                <span
                  className="transit-pulse-dot"
                  style={{ background: isDemoMode ? "#ffe0eb" : freshFleetCount > 0 ? "#86efac" : "#fcd34d" }}
                />
                <span>
                  {isDemoMode
                    ? "ডেমো মোড · সিমুলেটেড লোকেশন"
                    : freshFleetCount > 0
                    ? `${freshFleetCount}টি বাস লাইভ · GPS আপডেট হচ্ছে`
                    : wsStatus === "connected"
                    ? "লাইভ ফিড সংযুক্ত · GPS-এর অপেক্ষায়"
                    : wsStatus === "disconnected"
                    ? "সংযোগ ফিরিয়ে আনা হচ্ছে"
                    : "লাইভ ফিডে সংযুক্ত হচ্ছে"}
                </span>
              </div>
            </div>

            <button
              onClick={toggleDemoMode}
              className="transit-list-demo-btn"
              title={isDemoMode ? "লাইভ বাস ট্র্যাকিংয়ে ফিরুন" : "ডেমো বাস চালিয়ে দেখুন"}
              aria-label={isDemoMode ? "লাইভ বাস ট্র্যাকিংয়ে ফিরুন" : "ডেমো বাস চালিয়ে দেখুন"}
            >
              {isDemoMode ? <Radio size={13} /> : <Play size={13} />}
              <span>{isDemoMode ? "লাইভ" : "ডেমো"}</span>
            </button>
          </div>

          {/* Segmented 2-Tab Switcher: [তালিকা] / [ম্যাপ] */}
          <div className="transit-segmented-tabs">
            <button
              className="transit-seg-tab active"
              type="button"
              aria-selected="true"
            >
              <List size={15} />
              <span>তালিকা</span>
            </button>
            <button
              onClick={() => switchToMap()}
              className="transit-seg-tab"
              type="button"
              aria-selected="false"
            >
              <MapIcon size={15} />
              <span>ম্যাপ</span>
            </button>
          </div>
        </div>

        {/* Search Bar */}
        <div className="transit-list-search-wrapper">
          <div className="transit-list-search-input-box">
            <Search size={16} className="transit-search-icon" />
            <input
              type="text"
              placeholder="বাস নম্বর বা নাম; উৎস বা গন্তব্য"
              value={listSearch}
              onChange={(e) => setListSearch(e.target.value)}
              className="transit-list-search-input"
            />
            {listSearch && (
              <button
                onClick={() => setListSearch("")}
                className="transit-search-clear-btn"
                type="button"
                aria-label="Clear search"
              >
                <X size={15} />
              </button>
            )}
          </div>
        </div>

        {/* Filter Chips Row */}
        <div className="transit-filter-chips-row">
          <button
            onClick={() => setListFilter("all")}
            className={`transit-filter-chip ${listFilter === "all" ? "active" : ""}`}
            type="button"
          >
            সব বাস
          </button>
          <button
            onClick={() => setListFilter("driving")}
            className={`transit-filter-chip ${listFilter === "driving" ? "active" : ""}`}
            type="button"
          >
            চলন্ত বাস
          </button>
          <button
            onClick={() => setListFilter("standby")}
            className={`transit-filter-chip ${listFilter === "standby" ? "active" : ""}`}
            type="button"
          >
            স্ট্যান্ডবাই
          </button>
          <button
            onClick={() => setListFilter("to_campus")}
            className={`transit-filter-chip ${listFilter === "to_campus" ? "active" : ""}`}
            type="button"
          >
            ক্যাম্পাসমুখী
          </button>
          <button
            onClick={() => setListFilter("to_city")}
            className={`transit-filter-chip ${listFilter === "to_city" ? "active" : ""}`}
            type="button"
          >
            শহরমুখী
          </button>
          <button
            onClick={() => {
              setListSearch("");
              setListFilter("all");
            }}
            className="transit-filter-chip-icon"
            type="button"
            title="Reset filters"
          >
            <RefreshCw size={13} />
          </button>
        </div>

        {/* Bus Cards List */}
        <div className="transit-cards-list-container">
          {filteredFleet.length > 0 ? filteredFleet.map((bus) => {
            const nextInfo = getNextStopForBus(bus);
            const progressPct = getBusProgress(bus);
            const isBusOffline = bus.isStale || (bus.positionAgeSeconds != null && bus.positionAgeSeconds > 600);

            // Clean natural Bengali time format
            let ageText = "GPS সময় অজানা";
            if (bus.positionAgeSeconds != null) {
              const s = bus.positionAgeSeconds;
              if (s < 20) {
                ageText = "এইমাত্র";
              } else if (s < 60) {
                ageText = `${s} সে আগে`;
              } else if (s < 3600) {
                ageText = `${Math.floor(s / 60)} মি আগে`;
              } else {
                ageText = `${Math.floor(s / 3600)} ঘণ্টা আগে`;
              }
            }

            // Clean non-duplicated status metrics
            const progressLabel = bus.speed > 0 ? `${progressPct}% পথ শেষ` : (isBusOffline ? "টার্মিনাল স্ট্যান্ডবাই" : "ক্যাম্পাস স্ট্যান্ডবাই");
            const liveLabel = isBusOffline ? "GPS বিলম্বিত" : (bus.speed > 0 ? `${Math.round(bus.speed)} কিমি/ঘ` : "স্ট্যান্ডবাই");
            const liveDotColor = isBusOffline ? "#f97316" : (bus.speed > 0 ? "#10b981" : "#fbbf24");

            return (
              <div
                key={bus.id}
                onClick={() => {
                  setSelectedBusId(bus.id);
                  setDetailBusId(bus.id);
                  setActiveView("detail");
                }}
                className="transit-bus-card"
                role="button"
                tabIndex={0}
                aria-label={`${bus.nameBn}, ${formatBusShortId(bus.id)}. ${bus.routeName}. ${liveLabel}`}
                onKeyDown={(event) => {
                  if (event.target !== event.currentTarget) return;
                  if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    setSelectedBusId(bus.id);
                    setDetailBusId(bus.id);
                    setActiveView("detail");
                  }
                }}
              >
                {/* Header Row */}
                <div className="transit-bus-card-header">
                  <div className="transit-bus-card-titles">
                    <span className="transit-bus-name-bn">{bus.nameBn}</span>
                    <span className="transit-bus-id-chip" style={{ background: `${bus.color}15`, color: bus.color }}>
                      {formatBusShortId(bus.id)}
                    </span>
                  </div>
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      switchToMap(bus.id);
                    }}
                    className="transit-bus-card-map-action"
                    title="Track on map"
                    aria-label={`${bus.nameBn}-কে ম্যাপে দেখুন`}
                  >
                    <MapPin size={15} color={bus.color} />
                  </button>
                </div>

                {/* Route */}
                <div className="transit-bus-card-route-row">
                  <span className="transit-route-text">{bus.routeName}</span>
                </div>

                {/* Progress Metric Row */}
                <div className="transit-bus-metric-row">
                  <div className={`transit-progress-pill ${isBusOffline ? "stale" : ""}`}>
                    <span className="transit-progress-bar-icon" />
                    <span>{progressLabel}</span>
                  </div>
                  <span className="transit-dot-sep">•</span>
                  <div className="transit-status-live-pill">
                    <span
                      className="transit-pulse-dot"
                      style={{ background: liveDotColor }}
                    />
                    <span>{liveLabel}</span>
                  </div>
                  <span className="transit-update-time">{ageText}</span>
                </div>

                {/* Visual Progress Bar Track */}
                <div className="transit-route-progress-track">
                  <div
                    className="transit-route-progress-fill"
                    style={{
                      width: `${bus.speed > 0 ? progressPct : 12}%`,
                      background: `linear-gradient(90deg, ${bus.color}88, ${bus.color})`
                    }}
                  />
                  <div
                    className="transit-route-progress-pin"
                    style={{
                      left: `${bus.speed > 0 ? progressPct : 12}%`,
                      borderColor: bus.color
                    }}
                  >
                    <Bus size={11} color={bus.color} />
                  </div>
                </div>

                {/* Footer Times / Next Stop & User Distance */}
                <div className="transit-bus-card-footer">
                  <span className="transit-next-stop-tag">
                    পরবর্তী: <strong>{nextInfo.station.nameBn}</strong>
                  </span>
                  <div className="transit-card-user-distance-box">
                    {userLocation ? (
                      <span className="transit-user-distance-badge" title="আপনার বর্তমান অবস্থান থেকে এই বাসের দূরত্ব">
                        📍 আপনার থেকে {((getDistanceMeters(userLocation.lat, userLocation.lon, bus.lat, bus.lon)) / 1000).toFixed(1)} কিমি
                      </span>
                    ) : (
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          requestLocation();
                        }}
                        className="transit-request-loc-pill"
                        title="আপনার থেকে বাসের দূরত্ব জানতে লোকেশন চালু করুন"
                      >
                        📍 দূরত্ব মাপুন
                      </button>
                    )}
                  </div>
                </div>
              </div>
            );
          }) : (
            <div className="transit-empty-state" aria-live="polite">
              <div className="transit-empty-icon"><Radio size={26} /></div>
              <h2>{displayFleet.length === 0 ? "বর্তমানে কোনো বাস ট্র্যাকে নেই" : "এই ফিল্টারে কোনো বাস পাওয়া যায়নি"}</h2>
              <p>
                {displayFleet.length === 0
                  ? wsStatus === "connected"
                    ? "লাইভ ফিড সংযুক্ত রয়েছে। বিশ্ববিদ্যালয়ের বাস চলাচল শুরু করলে এবং ট্র্যাকার সংকেত পাঠালে এখানে স্বয়ংক্রিয়ভাবে দেখতে পাবেন।"
                    : "বিশ্ববিদ্যালয়ের লাইভ ট্র্যাকিং ফিডে সংযুক্ত হচ্ছে..."
                  : "অন্য ফিল্টার বেছে নিন বা সার্চ মুছে আবার দেখুন।"}
              </p>
              <div style={{ marginTop: 14 }}>
                {displayFleet.length === 0 ? (
                  <button
                    type="button"
                    onClick={toggleDemoMode}
                    className="transit-empty-action"
                    style={{ display: "inline-flex", alignItems: "center", gap: 8 }}
                  >
                    <Play size={14} /> <span>{isDemoMode ? "লাইভ মোডে ফিরুন" : "🚀 ডেমো বাস চালিয়ে দেখুন"}</span>
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => { setListSearch(""); setListFilter("all"); }}
                    className="transit-empty-action"
                  >
                    ফিল্টার মুছুন
                  </button>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    );
  };

  /* =========================================================================
     VIEW 2: BUS ROUTE & STOPPAGE TIMELINE DETAIL VIEW (Image 2)
     ========================================================================= */
  const renderDetailView = () => {
    if (!detailBus) return null;
    const detailNextInfo = getNextStopForBus(detailBus);

    return (
      <div className="transit-detail-screen">
        {/* Top App Bar with Back Button */}
        <div className="transit-detail-header-bar">
          <button
            onClick={() => setActiveView("list")}
            className="transit-detail-back-btn"
            title="তালিকা ভিউতে ফিরুন"
            aria-label="Back to bus list"
          >
            <ArrowLeft size={20} />
          </button>
          <div className="transit-detail-title-group">
            <div className="transit-detail-bus-name-row">
              <span className="transit-detail-bus-name">{detailBus.nameBn}</span>
              <span className="transit-detail-bus-chip">{formatBusShortId(detailBus.id)}</span>
            </div>
            <span className="transit-detail-subtitle">{detailBus.routeName}</span>
          </div>

          <div className="transit-detail-header-speed">
            <span
              className="transit-pulse-dot"
              style={{ background: detailBus.speed > 0 ? "#10b981" : "#fbbf24" }}
            />
            <span>{detailBus.speed > 0 ? `${Math.round(detailBus.speed)} কিমি/ঘ` : "স্ট্যান্ডবাই"}</span>
          </div>
        </div>

        {/* Live Hero Tracking Card */}
        <div className="transit-detail-hero-card" style={{ "--bus-accent": detailBus.color } as any}>
          <div className="transit-hero-top-row">
            <div>
              <span className="transit-hero-eyebrow">লাইভ লোকেশন ট্র্যাকিং</span>
              <h2 className="transit-hero-approaching-title">{detailNextInfo.station.nameBn}</h2>
              <span className="transit-hero-approaching-sub">{detailNextInfo.station.name}</span>
            </div>
            <div className="transit-hero-eta-box">
              {detailBus.speed > 0 ? (
                <>
                  <span className="transit-hero-eta-val">~{detailNextInfo.etaMinutes}</span>
                  <span className="transit-hero-eta-unit">মিনিট</span>
                </>
              ) : (
                <>
                  <span className="transit-hero-eta-val" style={{ fontSize: "13px", marginTop: "2px" }}>স্ট্যান্ডবাই</span>
                  <span className="transit-hero-eta-unit">~{detailNextInfo.etaMinutes} মি ড্রাইভ</span>
                </>
              )}
            </div>
          </div>

          <div className="transit-hero-stats-row">
            <div className="transit-hero-stat">
              <span className="transit-stat-label">দূরত্ব</span>
              <strong className="transit-stat-val">{detailNextInfo.distanceKm} কিমি</strong>
            </div>
            <div className="transit-hero-stat">
              <span className="transit-stat-label">বর্তমান গতি</span>
              <strong className="transit-stat-val">
                {detailBus.speed > 0 ? `${Math.round(detailBus.speed)} কিমি/ঘ` : "০ কিমি/ঘ"}
              </strong>
            </div>
            <div className="transit-hero-stat">
              <span className="transit-stat-label">স্ট্যাটাস</span>
              <strong className="transit-stat-val" style={{ color: detailBus.isStale ? "#f97316" : detailBus.speed > 0 ? "#10b981" : "#fbbf24" }}>
                {detailBus.isStale ? "GPS বিলম্বিত" : detailBus.speed > 0 ? "চলন্ত" : "স্ট্যান্ডবাই"}
              </strong>
            </div>
          </div>

          <button
            onClick={() => switchToMap(detailBus.id)}
            className="transit-hero-map-btn"
            type="button"
          >
            <MapIcon size={16} />
            <span>ম্যাপে ট্র্যাক করুন</span>
          </button>
        </div>

        {/* Corridor Schedule Meta Bar */}
        <div className="transit-timeline-meta-bar">
          <div className="transit-meta-left">
            <Clock size={14} />
            <span>আজকের রুট ট্র্যাকিং • {CORRIDOR_STATIONS.length}টি স্টপেজ</span>
          </div>
          <span className="transit-meta-tap-hint">স্টপেজে ট্যাপ করে অ্যালার্ট দিন</span>
        </div>

        {/* Stoppage Corridor Vertical Timeline */}
        <div className="transit-timeline-container">
          {CORRIDOR_STATIONS.map((station, idx) => {
            const approachingIdx = CORRIDOR_STATIONS.findIndex((s) => s.id === detailNextInfo.station.id);
            const isApproaching = station.id === detailNextInfo.station.id;
            const isAlerted = !!alertedStops[station.id];
            const isPassed = approachingIdx > -1 && idx < approachingIdx;

            // Strict sequential corridor distance calculation
            let displayKm = "0.00";
            let displayEta = "";

            if (isPassed) {
              displayKm = (CORRIDOR_MILESTONES_KM[idx] ?? 0).toFixed(2);
              displayEta = "অতিক্রম করেছে";
            } else if (isApproaching) {
              displayKm = detailNextInfo.distanceKm;
              displayEta = detailBus.speed > 0 ? `~${detailNextInfo.etaMinutes} মি` : `~${detailNextInfo.etaMinutes} মি ড্রাইভ`;
            } else {
              const baseDelta = Math.max(0.1, (CORRIDOR_MILESTONES_KM[idx] ?? 0) - (CORRIDOR_MILESTONES_KM[approachingIdx] ?? 0));
              const totalKm = baseDelta + parseFloat(detailNextInfo.distanceKm);
              displayKm = totalKm.toFixed(2);
              const driveEta = Math.max(1, Math.round((totalKm / Math.max(detailBus.speed, 25)) * 60));
              displayEta = `~${driveEta} মি`;
            }

            return (
              <div
                key={station.id}
                onClick={() => setSelectedStoppageAction(station)}
                className={`transit-timeline-item ${isApproaching ? "is-approaching" : ""} ${isPassed ? "is-passed" : ""}`}
              >
                {/* Timeline connector line */}
                {idx < CORRIDOR_STATIONS.length - 1 && <div className="transit-timeline-line" />}

                {/* Node Milestone */}
                <div className={`transit-timeline-node ${isApproaching ? "active-bus-node" : ""} ${isPassed ? "passed-node" : ""}`}>
                  {isApproaching ? (
                    <Bus size={13} color="#ffffff" />
                  ) : (
                    <span>{station.order}</span>
                  )}
                </div>

                {/* Stoppage Content */}
                <div className="transit-timeline-content">
                  <div className="transit-timeline-row">
                    <div>
                      <div className="transit-station-main-title">
                        <span>{station.nameBn}</span>
                        {isApproaching && <span className="transit-approaching-badge">NEXT</span>}
                      </div>
                      <span className="transit-station-sub-title">{station.name}</span>
                    </div>

                    <div className="transit-timeline-actions">
                      <div className="transit-timeline-eta-block">
                        <span className="transit-station-km">{displayKm} কিমি</span>
                        <span className={`transit-station-eta ${isPassed ? "eta-passed" : ""}`}>
                          {displayEta}
                        </span>
                      </div>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          toggleStopAlert(station.id);
                        }}
                        className={`transit-timeline-bell-btn ${isAlerted ? "armed" : ""}`}
                        title={isAlerted ? "অ্যালার্ট বন্ধ করুন" : "এই স্টপে পৌঁছালে অ্যালার্ট দিন"}
                        type="button"
                      >
                        <Bell size={14} />
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        {/* Sticky Bottom Summary Bar */}
        <div className="transit-detail-bottom-bar">
          <div className="transit-bottom-info">
            <span className="transit-bottom-label">পরবর্তী স্টপ:</span>
            <strong className="transit-bottom-name">{detailNextInfo.station.nameBn}</strong>
            <span className="transit-bottom-dist">({detailNextInfo.distanceKm} কিমি)</span>
          </div>

          <div className="transit-bottom-buttons">
            <button
              onClick={() => toggleStopAlert(detailNextInfo.station.id)}
              className={`transit-bottom-bell-btn ${alertedStops[detailNextInfo.station.id] ? "armed" : ""}`}
              title="Alert for next stop"
              type="button"
            >
              <Bell size={16} />
            </button>
            <button
              onClick={() => switchToMap(detailBus.id)}
              className="transit-bottom-map-fab"
              title="ম্যাপে দেখুন"
              type="button"
            >
              <MapIcon size={16} />
              <span>ম্যাপে দেখুন</span>
            </button>
          </div>
        </div>
      </div>
    );
  };

  /* =========================================================================
     STOPPAGE QUICK ACTION SHEET MODAL
     ========================================================================= */
  const renderStoppageActionModal = () => {
    if (!selectedStoppageAction || !detailBus) return null;
    const distM = getDistanceMeters(detailBus.lat, detailBus.lon, selectedStoppageAction.lat, selectedStoppageAction.lon);
    const distKm = (distM / 1000).toFixed(2);
    const isAlerted = !!alertedStops[selectedStoppageAction.id];

    return (
      <div className="transit-modal-overlay" onClick={() => setSelectedStoppageAction(null)}>
        <div className="transit-action-sheet" onClick={(e) => e.stopPropagation()}>
          <div className="transit-sheet-drag-pill" />
          <div className="transit-action-sheet-header">
            <div>
              <span className="transit-action-sheet-order">স্টপ #{selectedStoppageAction.order}</span>
              <h3 className="transit-action-sheet-name">{selectedStoppageAction.nameBn}</h3>
              <span className="transit-action-sheet-en">{selectedStoppageAction.name}</span>
            </div>
            <button
              onClick={() => setSelectedStoppageAction(null)}
              className="transit-sheet-close-btn"
              type="button"
            >
              <X size={18} />
            </button>
          </div>

          <div className="transit-action-sheet-dist-row">
            <MapPin size={15} color="#38bdf8" />
            <span>বর্তমান বাস থেকে দূরত্ব: <strong>{distKm} কিমি</strong></span>
          </div>

          <div className="transit-action-sheet-buttons">
            <button
              onClick={() => {
                toggleStopAlert(selectedStoppageAction.id);
                setSelectedStoppageAction(null);
              }}
              className={`transit-action-btn-alert ${isAlerted ? "armed" : ""}`}
              type="button"
            >
              <Bell size={16} />
              <span>{isAlerted ? "অ্যালার্ট বন্ধ করুন" : "এই স্টপে পৌঁছালে নোটিফাই করুন"}</span>
            </button>

            <button
              onClick={() => {
                switchToMap(detailBus.id);
                setSelectedStoppageAction(null);
              }}
              className="transit-action-btn-map"
              type="button"
            >
              <MapIcon size={16} />
              <span>ম্যাপে ট্র্যাক করুন</span>
            </button>
          </div>
        </div>
      </div>
    );
  };

  /* =========================================================================
     MAIN RENDER: Multi-View Routing
     ========================================================================= */
  return (
    <div className="transit-container" style={{ "--bus-accent-color": selectedBus?.color || "#0284c7" } as any}>
      {/* 1. List View (Image 1) */}
      {activeView === "list" && renderListView()}

      {/* 2. Detail Timeline View (Image 2) */}
      {activeView === "detail" && renderDetailView()}

      {/* 3. Stoppage Action Popover */}
      {selectedStoppageAction && renderStoppageActionModal()}

      {/* ===================================================================
          Map Canvas & Overlays (Always kept in DOM, shown when activeView === "map")
          =================================================================== */}
      <div
        className="transit-map-wrapper"
        style={{
          display: activeView === "map" ? "block" : "none",
          visibility: activeView === "map" ? "visible" : "hidden"
        }}
      >
        <div ref={mapContainerRef} className="transit-map-canvas" />
      </div>

      {activeView === "map" && (
        <>
          {/* Top Floating Overlay (z-20) */}
          <div className="transit-top-overlay">
            {/* Unified Sleek Floating Header Bar */}
            <div className="transit-header-pill unified-bar">
              <div className="transit-header-left">
                <div
                  className="transit-header-icon-box"
                  style={{
                    backgroundColor: "#fce8ef",
                    color: "#b71454",
                    border: "1px solid #f5c2d3"
                  }}
                >
                  <Bus size={18} />
                </div>

                <div className="transit-header-titles">
                  <div className="transit-header-title-row">
                    <span className="transit-header-main-title">MBSTU বাস ট্র্যাকার</span>
                    <span
                      className={`transit-status-pill ${isDemoMode ? "demo" : wsStatus === "connected" && freshFleetCount > 0 ? "live" : wsStatus === "disconnected" ? "reconnecting" : "waiting"}`}
                      aria-live="polite"
                    >
                      <span
                        className="transit-pulse-dot"
                        style={{
                          background: isDemoMode ? "#38bdf8" : wsStatus === "connected" && freshFleetCount > 0 ? "#34d399" : wsStatus === "disconnected" ? "#fb923c" : "#fbbf24"
                        }}
                      />
                      {isDemoMode
                        ? "ডেমো"
                        : wsStatus === "connected" && freshFleetCount > 0
                        ? `${freshFleetCount}টি লাইভ`
                        : wsStatus === "connected"
                        ? "GPS অপেক্ষা"
                        : wsStatus === "disconnected"
                        ? "সংযোগ ফিরছে"
                        : "সংযুক্ত হচ্ছে"}
                    </span>
                  </div>
                </div>
              </div>

              {/* Segmented Switcher + Demo in Header */}
              <div className="transit-header-actions-right">
                <div className="transit-segmented-tabs header-tabs">
                  <button
                    onClick={() => setActiveView("list")}
                    className="transit-seg-tab"
                    type="button"
                    aria-selected={false}
                  >
                    <List size={14} />
                    <span>তালিকা</span>
                  </button>
                  <button
                    onClick={() => switchToMap()}
                    className="transit-seg-tab active"
                    type="button"
                    aria-selected={true}
                  >
                    <MapIcon size={14} />
                    <span>ম্যাপ</span>
                  </button>
                </div>

                <button
                  onClick={toggleDemoMode}
                  className="transit-mode-toggle-btn"
                  title={isDemoMode ? "লাইভ বাস ট্র্যাকিংয়ে ফিরুন" : "ডেমো বাস দেখুন"}
                  aria-label={isDemoMode ? "লাইভ বাস ট্র্যাকিংয়ে ফিরুন" : "ডেমো বাস দেখুন"}
                >
                  {isDemoMode ? <Radio size={13} color="#38bdf8" /> : <Play size={13} color="#34d399" />}
                  <span>{isDemoMode ? "লাইভ" : "ডেমো"}</span>
                </button>
              </div>
            </div>

            {/* Horizontal Fleet Selector Chips */}
            <div className="transit-chips-scroll" role="group" aria-label="Select a bus">
              {displayFleet.length > 0 ? (
                displayFleet.map((bus) => {
                  const isSelected = bus.id === selectedBusId;
                  return (
                    <button
                      key={bus.id}
                      onClick={() => {
                        setSelectedBusId(bus.id);
                        setDetailBusId(bus.id);
                        if (mapRef.current) {
                          mapRef.current.flyTo([bus.lat, bus.lon], 17, { animate: true, duration: 0.8 });
                        }
                      }}
                      className={`transit-bus-chip ${isSelected ? "selected" : ""}`}
                      style={isSelected ? { borderColor: bus.color, boxShadow: `0 0 16px ${bus.color}60` } : {}}
                      aria-pressed={isSelected}
                      title={`${bus.nameBn} · ${bus.id}`}
                    >
                      <span className="transit-chip-dot" style={{ backgroundColor: bus.color, color: bus.color }} />
                      <span className="transit-chip-name">{bus.nameBn}</span>
                      <span className="transit-chip-id-badge">{formatBusShortId(bus.id)}</span>
                      <span className={`transit-chip-speed ${bus.isStale ? "stale" : bus.speed > 0 ? "moving" : ""}`}>
                        {bus.isStale ? "Delayed" : bus.speed > 0 ? `${Math.round(bus.speed)} km/h` : "Standby"}
                      </span>
                    </button>
                  );
                })
              ) : (
                <div className="transit-chip-waiting">
                  <span className="transit-pulse-dot" style={{ background: "#fbbf24" }} />
                  <span>
                    {wsStatus === "connected"
                      ? "Connected · waiting for a bus GPS update"
                      : wsStatus === "disconnected"
                      ? "Connection lost · trying again"
                      : "Connecting to the university GPS feed"}
                  </span>
                </div>
              )}
            </div>
          </div>

          {/* Off-screen Bus Recenter Floating Pill */}
          {isBusOffscreen && selectedBus && (
            <button
              onClick={centerSelectedBus}
              className="transit-offscreen-pill"
              aria-label={`Center map on ${selectedBus.nameBn}`}
            >
              <Compass size={14} className="transit-offscreen-spin" />
              <span><strong>{selectedBus.nameBn}</strong> is off-screen · Tap to view</span>
            </button>
          )}

          {arrivalNotice && (
            <div className="transit-arrival-toast" role="status">
              <CheckCircle2 size={18} />
              <span><strong>{arrivalNotice.busName}</strong> is near {arrivalNotice.stopName}</span>
              <button type="button" onClick={() => setArrivalNotice(null)} aria-label="Dismiss arrival alert">
                <X size={16} />
              </button>
            </div>
          )}

          {/* Right-Side Floating Map Controls (z-20) */}
          <div className={`transit-fab-controls ${sheetExpanded ? "sheet-expanded" : ""}`}>
            <div className="transit-fab-group">
              <button
                onClick={() => mapRef.current?.zoomIn()}
                className="transit-fab-btn"
                title="Zoom In"
                aria-label="Zoom in on map"
              >
                <Plus size={18} />
              </button>
              <div className="transit-fab-divider" />
              <button
                onClick={() => mapRef.current?.zoomOut()}
                className="transit-fab-btn"
                title="Zoom Out"
                aria-label="Zoom out on map"
              >
                <Minus size={18} />
              </button>
            </div>

            {selectedBus && (
              <button
                onClick={centerSelectedBus}
                className={`transit-fab-btn ${!isBusOffscreen ? "active-track" : ""}`}
                title={`Center map on ${selectedBus.nameBn}`}
                aria-label={`Center map on selected bus ${selectedBus.nameBn}`}
              >
                <LocateFixed size={18} color={!isBusOffscreen ? "#38bdf8" : "#94a3b8"} />
              </button>
            )}

            <button
              onClick={() => setSatelliteView((prev) => !prev)}
              className="transit-fab-btn"
              title={satelliteView ? "Switch to Street Roadmap" : "Switch to Google Satellite"}
              aria-label={satelliteView ? "Switch to street map" : "Switch to satellite map"}
            >
              <Layers size={18} color={satelliteView ? "#38bdf8" : "#94a3b8"} />
            </button>

            <button
              onClick={recenterCampus}
              className="transit-fab-btn"
              title="Fly to MBSTU Santosh Campus"
              aria-label="Recenter map on campus"
            >
              <MapPin size={18} color="#38bdf8" />
            </button>

            {/* Locate Me / User Location FAB */}
            <button
              onClick={() => {
                if (userLocation && mapRef.current) {
                  mapRef.current.flyTo([userLocation.lat, userLocation.lon], 16, { animate: true, duration: 0.8 });
                } else {
                  requestLocation();
                }
              }}
              className={`transit-fab-btn ${userLocation ? "has-user-loc" : ""}`}
              title={userLocation ? "আমার বর্তমান অবস্থান দেখুন" : "লোকেশন চালু করে আমার অবস্থান দেখুন"}
              aria-label="Locate me on map"
              type="button"
            >
              <Navigation size={18} color={userLocation ? "#10b981" : "#94a3b8"} />
            </button>
          </div>

          {/* C. Material 3 Bottom Sheet (Appears ONLY when a bus is tapped, disappears when clicking elsewhere) */}
          {selectedBus && (
            <div
              className={`transit-bottom-sheet ${sheetExpanded ? "expanded" : "collapsed"}`}
              style={{ "--bus-accent": selectedBus.color } as any}
            >
              <div
                className="transit-sheet-drag-handle"
                onClick={() => setSheetExpanded((prev) => !prev)}
                role="button"
                tabIndex={0}
                aria-label={sheetExpanded ? "Collapse route panel" : "Expand route panel"}
              >
                <div className="transit-sheet-handle-pill" />
              </div>

              {/* Header Row */}
              <div className="transit-sheet-header">
                <div className="transit-sheet-header-left">
                  <div
                    className="transit-sheet-bus-badge"
                    style={{
                      backgroundColor: `${selectedBus.color}22`,
                      borderColor: selectedBus.color,
                      color: selectedBus.color
                    }}
                  >
                    <Bus size={13} />
                    <span className="transit-badge-id">{formatBusShortId(selectedBus.id)}</span>
                  </div>

                  <div className="transit-sheet-bus-titles">
                    <div className="transit-bus-name-row">
                      <h2 className="transit-bus-name-bn">{selectedBus.nameBn}</h2>
                      <span className="transit-bus-name-en">({selectedBus.nameEn})</span>
                    </div>
                    <span className="transit-bus-route-name">{selectedBus.routeName}</span>
                  </div>
                </div>

                <div className="transit-sheet-header-right">
                  <div className={`transit-speed-pill ${selectedBus.isStale ? "stale" : selectedBus.speed > 0 ? "active" : ""}`}>
                    <Navigation size={12} className={selectedBus.speed > 0 ? "spin-slow" : ""} />
                    <span>
                      {selectedBus.isStale
                        ? "GPS বিলম্বিত"
                        : selectedBus.speed > 0
                        ? `${Math.round(selectedBus.speed)} কিমি/ঘ`
                        : "স্ট্যান্ডবাই"}
                    </span>
                  </div>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setSelectedBusId("");
                    }}
                    className="transit-sheet-close-btn"
                    title="Close"
                    aria-label="Close bottom sheet"
                    type="button"
                  >
                    <X size={15} />
                  </button>
                </div>
              </div>

              {/* Approaching Stop Card */}
              {nextApproachingStopInfo && (
                <div className="transit-approaching-card">
                  <div className="transit-approaching-left">
                    <div className="transit-approaching-icon-box">
                      <Navigation size={15} />
                    </div>
                    <div className="transit-approaching-text">
                      <span className="transit-approaching-eyebrow">পরবর্তী স্টপ</span>
                      <h3 className="transit-approaching-name">{nextApproachingStopInfo.station.nameBn}</h3>
                      <span className="transit-approaching-bn">
                        {nextApproachingStopInfo.station.name} • {nextApproachingStopInfo.distanceKm} কিমি
                      </span>
                    </div>
                  </div>

                  <div className="transit-approaching-right">
                    <div className="transit-eta-badge">
                      <span className="transit-eta-val">
                        {selectedBus.speed > 0 ? `~${nextApproachingStopInfo.etaMinutes} মি` : "স্ট্যান্ডবাই"}
                      </span>
                      <span className="transit-eta-sub">
                        {selectedBus.speed > 0 ? "আনুমানিক সময়" : `~${nextApproachingStopInfo.etaMinutes} মি ড্রাইভ`}
                      </span>
                    </div>

                    <button
                      onClick={() => toggleStopAlert(nextApproachingStopInfo.station.id)}
                      className={`transit-stop-bell-btn ${alertedStops[nextApproachingStopInfo.station.id] ? "armed" : ""}`}
                      title={alertedStops[nextApproachingStopInfo.station.id] ? "অ্যালার্ট সক্রিয়" : "অ্যালার্ট দিন"}
                      type="button"
                    >
                      <Bell size={15} />
                    </button>
                  </div>
                </div>
              )}

              {/* Footer Row */}
              <div className="transit-sheet-footer-row">
                <div className="transit-status-indicator">
                  <span
                    className="transit-pulse-dot"
                    style={{ background: selectedBus.speed > 0 ? "#10b981" : "#fbbf24" }}
                  />
                  <span>{selectedBus.status}</span>
                </div>

                <div className="transit-sheet-actions-group">
                  <button
                    onClick={() => {
                      setDetailBusId(selectedBus.id);
                      setActiveView("detail");
                    }}
                    className="transit-sheet-detail-action"
                    type="button"
                  >
                    <span>সম্পূর্ণ রুট ও সময়সূচী</span>
                    <ChevronRight size={14} />
                  </button>
                </div>
              </div>

              {sheetExpanded && (
                <div className="transit-stepper-scroll-container">
                  <div className="transit-stepper-header-row">
                    <span className="transit-stepper-title">STREET CORRIDOR STOPPAGES ({CORRIDOR_STATIONS.length})</span>
                    <span className="transit-stepper-hint">TAP 🔔 TO SET ARRIVAL ALERT</span>
                  </div>

                  <div className="transit-stepper-list">
                    {CORRIDOR_STATIONS.map((st, idx) => {
                      const isApproaching = nextApproachingStopInfo?.station.id === st.id;
                      const isAlerted = !!alertedStops[st.id];
                      const distM = getDistanceMeters(selectedBus.lat, selectedBus.lon, st.lat, st.lon);
                      const distKm = (distM / 1000).toFixed(2);
                      const etaM = Math.max(1, Math.round((distM / 1000 / Math.max(selectedBus.speed, 25)) * 60));

                      return (
                        <div
                          key={st.id}
                          onClick={() => setSelectedStoppageAction(st)}
                          className={`transit-stepper-row ${isApproaching ? "approaching" : ""}`}
                          role="button"
                          tabIndex={0}
                        >
                          {idx < CORRIDOR_STATIONS.length - 1 && <div className="transit-stepper-line" />}
                          <div className="transit-stepper-node">{st.order}</div>

                          <div className="transit-stepper-content">
                            <div className="transit-stepper-title-row">
                              <span className="transit-stepper-stop-name">{st.name}</span>
                              <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                                {isApproaching && <span className="transit-stepper-badge-next">NEXT</span>}
                                <button
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    toggleStopAlert(st.id);
                                  }}
                                  className={`transit-stepper-alert-btn ${isAlerted ? "armed" : ""}`}
                                  type="button"
                                  title={isAlerted ? "Turn off arrival alert" : "Alert me"}
                                >
                                  <Bell size={14} />
                                </button>
                              </div>
                            </div>

                            <div className="transit-stepper-sub">
                              <span>{st.nameBn}</span>
                              <span>•</span>
                              <span>{distKm} km</span>
                              <span>•</span>
                              <span style={{ color: isApproaching ? "#38bdf8" : "#94a3b8" }}>
                                {selectedBus.isStale
                                  ? "GPS delayed"
                                  : `~${etaM} min approximate ${selectedBus.speed === 0 ? "drive" : "ETA"}`}
                              </span>
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}

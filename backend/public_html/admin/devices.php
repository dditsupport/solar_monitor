<?php
// Device management moved to /dashboard/devices.php, where owners manage
// their own devices and admins every device.
declare(strict_types=1);
require_once __DIR__ . '/../api/_db.php';
require_admin();
header('Location: /dashboard/devices.php', true, 302);

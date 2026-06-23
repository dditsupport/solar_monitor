<?php
// Entry point for /solar/ — kicks the user into the dashboard, which in
// turn bounces unauthenticated visitors to the login page.
declare(strict_types=1);
header('Location: /solar/dashboard/');
exit;
